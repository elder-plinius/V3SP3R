import { WebSocketServer, WebSocket } from "ws";
import { createServer } from "http";

/**
 * V3SP3R ↔ Mentra Glasses Bridge Server
 *
 * This server acts as a relay between:
 *   - Smart glasses (MentraOS, Even Realities, Vuzix, etc.)
 *   - V3SP3R Android app
 *
 * Architecture:
 *   [Glasses] → (MentraOS Cloud) → [This Bridge] → (WebSocket) → [V3SP3R App]
 *
 * Modes:
 *   1. Standalone relay (default) — glasses connect directly via WebSocket
 *   2. MentraOS mode — set MENTRA_API_KEY env var to enable native SDK integration
 *
 * Deployment: Railway, Fly.io, Render, or local with ngrok for dev.
 */

// ==================== Wire Protocol ====================

interface GlassesMessage {
  type:
    | "VOICE_TRANSCRIPTION"
    | "CAMERA_PHOTO"
    | "VOICE_COMMAND"
    | "AI_RESPONSE"
    | "STATUS_UPDATE"
    | "CONFIG";
  text?: string;
  imageBase64?: string;
  imageMimeType?: string;
  displayText?: string;
  isFinal?: boolean;
  metadata?: Record<string, string>;
}

// ==================== Client Tracking ====================

interface ConnectedClient {
  ws: WebSocket;
  type: "glasses" | "vesper" | "unknown";
  connectedAt: number;
}

const clients = new Map<WebSocket, ConnectedClient>();

function getVesperClients(): WebSocket[] {
  return Array.from(clients.entries())
    .filter(([, c]) => c.type === "vesper")
    .map(([ws]) => ws);
}

function getGlassesClients(): WebSocket[] {
  return Array.from(clients.entries())
    .filter(([, c]) => c.type === "glasses")
    .map(([ws]) => ws);
}

export function broadcast(targets: WebSocket[], message: GlassesMessage) {
  const payload = JSON.stringify(message);
  for (const ws of targets) {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(payload);
    }
  }
}

// ==================== Sailor Mouth Mode ====================

let sailorMouthEnabled = false;

// Track session context so greetings feel natural
let wakeCount = 0;
let lastWakeTimestamp = 0;
let lastTaskTimestamp = 0; // when we last forwarded a VOICE_COMMAND

const IDLE_THRESHOLD_MS = 5 * 60 * 1000; // 5 min = "been a while"

// ── Normal greetings (context-aware) ────────────────────────
const GREETINGS_FIRST = [
  "hey! what's up?",
  "hi fren, what do you need?",
  "hey there, I'm listening",
];

const GREETINGS_RETURN = [
  "welcome back, what's next?",
  "hey again! what do you need?",
  "back at it, what's the move?",
];

const GREETINGS_ACTIVE = [
  "yeah?",
  "what's up?",
  "listening",
  "go ahead",
  "I'm here",
];

const GREETINGS_WITH_COMMAND = [
  "on it",
  "got it",
  "roger that",
  "working on it",
  "let me check",
];

// ── Sailor mouth greetings ──────────────────────────────────
const SAILOR_FIRST = [
  "sup bitch, what we hackin?",
  "HACK THE PLANET, MOTHERFUCKER!",
  "yo what's good, you beautiful degenerate",
];

const SAILOR_RETURN = [
  "oh shit, you're back! what now?",
  "bout damn time you called me",
  "missed you, asshole. what's the play?",
];

const SAILOR_ACTIVE = [
  "yeah bitch?",
  "what now?",
  "talk to me",
  "spit it out",
  "I'm all ears, fucker",
];

const SAILOR_WITH_COMMAND = [
  "on it, you crazy bastard",
  "fuck yeah, let's go",
  "hell yeah, hold my beer",
  "let's break some shit",
  "ready to fuck shit up, captain",
  "at your service, you glorious bastard",
  "what's crackin, you magnificent asshole",
  "let's go, you crazy son of a bitch",
];

function pick(arr: string[]): string {
  return arr[Math.floor(Math.random() * arr.length)];
}

function getWakeGreeting(hasCommand: boolean): string {
  const now = Date.now();
  const timeSinceLastWake = now - lastWakeTimestamp;
  const isFirstWake = wakeCount === 0;
  const isReturning = timeSinceLastWake > IDLE_THRESHOLD_MS;
  // "Active" = last task was recent, user is in the middle of a workflow
  const isActive = (now - lastTaskTimestamp) < IDLE_THRESHOLD_MS && !isFirstWake;

  wakeCount++;
  lastWakeTimestamp = now;

  if (hasCommand) {
    return pick(sailorMouthEnabled ? SAILOR_WITH_COMMAND : GREETINGS_WITH_COMMAND);
  }
  if (isFirstWake) {
    return pick(sailorMouthEnabled ? SAILOR_FIRST : GREETINGS_FIRST);
  }
  if (isReturning) {
    return pick(sailorMouthEnabled ? SAILOR_RETURN : GREETINGS_RETURN);
  }
  // Mid-session, keep it short
  return pick(sailorMouthEnabled ? SAILOR_ACTIVE : GREETINGS_ACTIVE);
}

// ==================== WebSocket Relay Server ====================

const PORT = parseInt(process.env.PORT || "8089", 10);
const wss = new WebSocketServer({ port: PORT });

console.log(`V3SP3R Glasses Bridge running on port ${PORT}`);

// ==================== Heartbeat (keeps connections alive through proxies) ====================

const HEARTBEAT_INTERVAL_MS = 25_000; // 25s — safely under Cloudflare's 100s idle timeout

function heartbeat(this: WebSocket & { isAlive?: boolean }) {
  this.isAlive = true;
}

const heartbeatTimer = setInterval(() => {
  for (const ws of wss.clients) {
    const sock = ws as WebSocket & { isAlive?: boolean };
    if (sock.isAlive === false) {
      console.log("Terminating unresponsive client");
      clients.delete(ws);
      return ws.terminate();
    }
    sock.isAlive = false;
    ws.ping();
  }
}, HEARTBEAT_INTERVAL_MS);

wss.on("close", () => clearInterval(heartbeatTimer));

wss.on("connection", (ws, req) => {
  const clientId = req.headers["x-vesper-client"] as string | undefined;
  const clientType = clientId === "v3sp3r-android" ? "vesper" : "unknown";

  const client: ConnectedClient = {
    ws,
    type: clientType as "glasses" | "vesper" | "unknown",
    connectedAt: Date.now(),
  };
  clients.set(ws, client);
  (ws as WebSocket & { isAlive?: boolean }).isAlive = true;
  ws.on("pong", heartbeat);

  console.log(
    `Client connected: ${client.type} (${clients.size} total) from ${req.socket.remoteAddress}`
  );

  ws.on("message", (data) => {
    try {
      const message: GlassesMessage = JSON.parse(data.toString());
      handleMessage(ws, message);
    } catch (e) {
      console.warn("Invalid message:", (e as Error).message);
    }
  });

  ws.on("close", () => {
    clients.delete(ws);
    console.log(`Client disconnected (${clients.size} remaining)`);
  });

  ws.on("error", (err) => {
    console.error("WebSocket error:", err.message);
    clients.delete(ws);
  });

  ws.send(
    JSON.stringify({
      type: "STATUS_UPDATE",
      text: "Connected to V3SP3R Glasses Bridge",
      metadata: {
        glasses_connected: getGlassesClients().length.toString(),
        vesper_connected: getVesperClients().length.toString(),
      },
    } satisfies GlassesMessage)
  );
});

function handleMessage(sender: WebSocket, message: GlassesMessage) {
  const client = clients.get(sender);
  if (!client) return;

  switch (message.type) {
    case "VOICE_TRANSCRIPTION":
    case "CAMERA_PHOTO":
    case "VOICE_COMMAND":
      if (client.type === "unknown") {
        client.type = "glasses";
        console.log("Client identified as glasses");
      }
      broadcast(getVesperClients(), message);
      break;

    case "AI_RESPONSE":
    case "STATUS_UPDATE":
      if (client.type === "unknown") {
        client.type = "vesper";
        console.log("Client identified as vesper");
      }
      broadcast(getGlassesClients(), message);
      break;

    case "CONFIG":
      if (message.metadata) {
        if ("sailor_mouth" in message.metadata) {
          sailorMouthEnabled = message.metadata.sailor_mouth === "true";
          console.log(`[Config] Sailor mouth: ${sailorMouthEnabled}`);
        }
      }
      break;
  }
}

// ==================== MentraOS Native Integration ====================
// Activated when MENTRA_API_KEY is set.
// Uses @mentra/sdk v2.1.29 — verified API surface.

/**
 * Wait for the MentraOS session's internal WebSocket to be ready.
 * The SDK fires onSession before the glasses WebSocket handshake completes,
 * so calling session.layouts.showTextWall (→ AppSession.send) immediately
 * throws "WebSocket connection not established".
 *
 * We poll a lightweight send to detect readiness (max ~5 seconds).
 */
async function waitForSessionReady(
  session: any,
  sessionId: string,
  maxWaitMs = 5000,
  intervalMs = 250
): Promise<void> {
  const deadline = Date.now() + maxWaitMs;
  while (Date.now() < deadline) {
    try {
      // Probe with a short-lived text wall — if send() doesn't throw, we're ready
      await session.layouts.showTextWall("", { durationMs: 1 });
      return;
    } catch {
      // WebSocket not ready yet — wait and retry
      await new Promise((r) => setTimeout(r, intervalMs));
    }
  }
  console.warn(
    `[MentraOS] Session ${sessionId} WebSocket not ready after ${maxWaitMs}ms — proceeding anyway`
  );
}

async function startMentraIntegration() {
  const apiKey = process.env.MENTRA_API_KEY;
  if (!apiKey) {
    console.log(
      "MentraOS: No MENTRA_API_KEY set — running in standalone relay mode."
    );
    console.log(
      "  Set MENTRA_API_KEY to enable native Mentra glasses integration."
    );
    return;
  }

  try {
    // Dynamic import so the bridge works without @mentra/sdk installed
    const { AppServer } = await import("@mentra/sdk");

    const packageName =
      process.env.MENTRA_PACKAGE_NAME || "com.vesper.glasses";
    const mentraPort = parseInt(process.env.MENTRA_PORT || "3000", 10);

    class VesperGlassesBridge extends AppServer {
      protected async onSession(
        session: any,
        sessionId: string,
        userId: string
      ) {
        console.log(
          `[MentraOS] Session started: ${sessionId} (user: ${userId})`
        );

        // Wait for the SDK's internal WebSocket to the glasses to be ready.
        // AppSession.send() throws synchronously if called before the
        // connection is established, which would kill onSession entirely.
        await waitForSessionReady(session, sessionId);

        // Show welcome on glasses HUD (non-fatal — don't let this kill setup)
        try {
          await session.layouts.showTextWall("V3SP3R Connected", {
            durationMs: 3000,
          });
        } catch (e) {
          console.warn(
            `[MentraOS] Welcome display failed (non-fatal): ${(e as Error).message}`
          );
        }

        // ── Wake word state ─────────────────────────────────────
        // "Hey Vesper" activates the agent. Two modes:
        //   1. "Hey Vesper, <command>" — immediate execution
        //   2. "Hey Vesper" alone — arms for the next utterance
        let awaitingCommand = false;
        let awaitingTimer: ReturnType<typeof setTimeout> | null = null;
        const WAKE_TIMEOUT_MS = 15_000; // 15s to say follow-up command

        function resetAwaitingState() {
          awaitingCommand = false;
          if (awaitingTimer) {
            clearTimeout(awaitingTimer);
            awaitingTimer = null;
          }
        }

        // ── Voice → V3SP3R ────────────────────────────────────────
        session.events.onTranscription(
          async (data: {
            text: string;
            isFinal: boolean;
            transcribeLanguage?: string;
          }) => {
            if (!data.isFinal || !data.text.trim()) return;

            const rawText = data.text.trim();
            const lowerText = rawText.toLowerCase();

            // ── Wake word detection ──────────────────────────────
            const wakePatterns = [
              /^hey\s+vesper[\s,.:!]*(.*)$/i,
              /^vesper[\s,.:!]*(.*)$/i,
            ];

            let command: string | null = null;
            let isWakeTriggered = false;

            for (const pattern of wakePatterns) {
              const match = rawText.match(pattern);
              if (match) {
                isWakeTriggered = true;
                command = match[1]?.trim() || null;
                break;
              }
            }

            if (isWakeTriggered) {
              const hasCommand = !!(command && command.length > 0);

              // Greet on wake — cancel any in-flight speech first
              try { await session.audio.stop(); } catch { /* no-op */ }
              session.audio
                .speak(getWakeGreeting(hasCommand), {
                  language: "en-GB",
                  voice: "en-GB-Wavenet-F",
                })
                .catch(() => {});

              if (hasCommand) {
                // "Hey Vesper, scan this" — immediate command
                console.log(`[MentraOS] Wake + command: "${command}"`);
                lastTaskTimestamp = Date.now();
                resetAwaitingState();

                broadcast(getVesperClients(), {
                  type: "VOICE_COMMAND",
                  text: command!,
                  metadata: {
                    source: "mentra",
                    sessionId,
                    language: data.transcribeLanguage || "en",
                    wake_word: "true",
                  },
                });

                // Check for vision triggers
                checkVisionTrigger(session, command!);
              } else {
                // "Hey Vesper" alone — arm for next utterance
                console.log(`[MentraOS] Wake word detected — awaiting command`);
                resetAwaitingState();
                awaitingCommand = true;
                awaitingTimer = setTimeout(() => {
                  console.log(`[MentraOS] Wake word timed out`);
                  awaitingCommand = false;
                  awaitingTimer = null;
                  try {
                    session.layouts
                      .showTextWall("Vesper: timed out", { durationMs: 2000 })
                      .catch(() => {});
                  } catch {
                    // Session WebSocket may have disconnected
                  }
                }, WAKE_TIMEOUT_MS);

                try {
                  session.layouts
                    .showTextWall("Vesper: listening...", { durationMs: 3000 })
                    .catch(() => {});
                } catch {
                  // Session WebSocket may have disconnected
                }

                broadcast(getVesperClients(), {
                  type: "STATUS_UPDATE",
                  text: "Vesper listening...",
                  metadata: { source: "mentra", wake_word: "true" },
                });
              }
              return;
            }

            // ── Armed follow-up: treat this as the command ───────
            if (awaitingCommand) {
              console.log(`[MentraOS] Follow-up command: "${rawText}"`);
              lastTaskTimestamp = Date.now();
              resetAwaitingState();

              broadcast(getVesperClients(), {
                type: "VOICE_COMMAND",
                text: rawText,
                metadata: {
                  source: "mentra",
                  sessionId,
                  language: data.transcribeLanguage || "en",
                  wake_word: "true",
                },
              });

              checkVisionTrigger(session, rawText);
              return;
            }

            // ── No wake word, not armed — relay as passive transcription ──
            // (only sent if auto-send is on in the Android app)
            console.log(`[MentraOS] Voice (passive): "${rawText}"`);
            broadcast(getVesperClients(), {
              type: "VOICE_TRANSCRIPTION",
              text: rawText,
              isFinal: true,
              metadata: {
                source: "mentra",
                sessionId,
                language: data.transcribeLanguage || "en",
              },
            });
          }
        );

        // ── Camera events → V3SP3R ───────────────────────────────
        try {
          session.events.onPhotoTaken(
            (data: { photoData: ArrayBuffer }) => {
              const imageBase64 = Buffer.from(data.photoData).toString("base64");
              console.log(`[MentraOS] Photo captured: ${imageBase64.length} chars base64`);

              broadcast(getVesperClients(), {
                type: "CAMERA_PHOTO",
                text: "What am I looking at?",
                imageBase64,
                imageMimeType: "image/jpeg",
                metadata: { source: "mentra", sessionId },
              });
            }
          );
          console.log(`[MentraOS] Photo listener registered`);
        } catch (e) {
          console.warn(`[MentraOS] Failed to register photo listener: ${(e as Error).message}`);
        }

        // ── Session keepalive ────────────────────────────────────────
        // Periodically poke the session so MentraOS doesn't idle-kill the
        // mini app. Also serves as a health-check log line.
        const keepaliveTimer = setInterval(async () => {
          try {
            if (session.isConnected?.() ?? true) {
              await session.layouts.showTextWall("V3SP3R Active", {
                durationMs: 1000,
              });
            }
          } catch {
            // Session may have ended — cleanup will handle it
          }
        }, 30_000);

        // Register a virtual "glasses" client for this MentraOS session
        // so relay broadcasts reach it.
        const virtualWs = {
          readyState: WebSocket.OPEN,
          send: (payload: string) => {
            try {
              const message: GlassesMessage = JSON.parse(payload);
              handleMentraResponse(session, message);
            } catch {
              // ignore
            }
          },
        } as unknown as WebSocket;

        clients.set(virtualWs, {
          ws: virtualWs,
          type: "glasses",
          connectedAt: Date.now(),
        });

        // Cleanup on session end
        session.addCleanupHandler?.(() => {
          clearInterval(keepaliveTimer);
          clients.delete(virtualWs);
          console.log(`[MentraOS] Session ended: ${sessionId}`);
        });
      }
    }

    const app = new VesperGlassesBridge({
      packageName,
      apiKey,
      port: mentraPort,
    });

    await app.start();
    console.log(
      `[MentraOS] Native integration active on port ${mentraPort}`
    );
    console.log(`[MentraOS] Package: ${packageName}`);
  } catch (e) {
    console.warn(
      "[MentraOS] SDK not available — running in standalone relay mode."
    );
    console.warn(`  Install with: npm install @mentra/sdk`);
    console.warn(`  Error: ${(e as Error).message}`);
  }
}

/** Check if text contains a vision trigger and capture if so. */
function checkVisionTrigger(session: any, text: string) {
  const visionTriggers = [
    "what am i looking at",
    "what do you see",
    "what is this",
    "analyze this",
    "scan this",
    "identify this",
    "take a photo",
    "take a picture",
    "capture this",
    "screenshot",
  ];
  const lower = text.toLowerCase();
  if (visionTriggers.some((t) => lower.includes(t))) {
    captureAndAnalyze(session, text);
  }
}

/** Capture a photo from glasses camera and send for vision analysis. */
async function captureAndAnalyze(session: any, prompt: string) {
  try {
    try {
      await session.layouts.showTextWall("Capturing...", { durationMs: 2000 });
    } catch { /* WebSocket may not be ready */ }

    // Check if camera API is available
    if (!session.camera || typeof session.camera.requestPhoto !== "function") {
      console.error("[MentraOS] Camera API not available on this session");
      try {
        await session.layouts.showTextWall("Camera not available", { durationMs: 3000 });
      } catch { /* ignore */ }
      return;
    }

    console.log("[MentraOS] Requesting photo from glasses camera...");
    const photo = await session.camera.requestPhoto({
      metadata: { reason: "vesper-vision" },
    });

    if (!photo) {
      console.error("[MentraOS] Photo request returned null");
      return;
    }

    // Mentra SDK returns photo data under varying field names depending on version.
    // Try all known fields: photoData (docs), data, buffer, image, photo
    const rawData = photo.photoData ?? photo.data ?? photo.buffer ?? photo.image ?? photo.photo;

    if (!rawData) {
      console.error("[MentraOS] Photo object has no recognizable data field. Keys:", Object.keys(photo).join(", "));
      // If the SDK returned a URL instead of raw bytes, log it
      if (photo.url || photo.photoUrl) {
        console.log("[MentraOS] Photo may be URL-based:", photo.url || photo.photoUrl);
      }
      return;
    }

    const imageBase64 = Buffer.from(rawData).toString("base64");
    console.log(`[MentraOS] Photo received: ${imageBase64.length} chars, mime: ${photo.mimeType || "image/jpeg"}`);

    broadcast(getVesperClients(), {
      type: "CAMERA_PHOTO",
      text: prompt,
      imageBase64,
      imageMimeType: photo.mimeType || "image/jpeg",
      metadata: { source: "mentra" },
    });

    try {
      await session.layouts.showTextWall("Analyzing...", { durationMs: 3000 });
    } catch { /* WebSocket may not be ready */ }
  } catch (e) {
    const errMsg = (e as Error).message;
    console.error("[MentraOS] Vision capture failed:", errMsg);
    try {
      await session.layouts.showTextWall(`Camera error: ${errMsg.slice(0, 40)}`, { durationMs: 3000 });
    } catch { /* ignore */ }
  }
}

/** Handle V3SP3R AI responses — speak + display on MentraOS glasses. */
async function handleMentraResponse(session: any, message: GlassesMessage) {
  try {
    switch (message.type) {
      case "AI_RESPONSE":
        if (message.text) {
          // Cancel any in-flight speech to prevent overlapping voices
          try { await session.audio.stop(); } catch { /* no-op if not supported */ }
          await session.audio.speak(message.text, {
            language: "en-GB",
            voice: "en-GB-Wavenet-F",
          });
        }
        if (message.displayText) {
          await session.layouts.showReferenceCard({
            title: "V3SP3R",
            text: message.displayText,
          });
        }
        break;

      case "STATUS_UPDATE":
        if (message.text) {
          await session.layouts.showTextWall(message.text, {
            durationMs: 5000,
          });
          // Speak short status updates so user gets audio feedback
          // (e.g. "Thinking...", "Executing read_file"). Skip long ones.
          if (message.text.length <= 50) {
            try { await session.audio.stop(); } catch { /* no-op */ }
            await session.audio.speak(message.text, {
              language: "en-GB",
              voice: "en-GB-Wavenet-F",
            });
          }
        }
        break;
    }
  } catch (e) {
    console.warn("[MentraOS] Response delivery failed:", (e as Error).message);
  }
}

// Start MentraOS integration (non-blocking — falls back to relay-only mode)
startMentraIntegration();

// ==================== Health Check ====================

const HTTP_PORT = parseInt(process.env.HTTP_PORT || "8088", 10);

const httpServer = createServer((req, res) => {
  if (req.url === "/health") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(
      JSON.stringify({
        status: "ok",
        glasses: getGlassesClients().length,
        vesper: getVesperClients().length,
        mentra: !!process.env.MENTRA_API_KEY,
        uptime: process.uptime(),
      })
    );
  } else {
    res.writeHead(404);
    res.end();
  }
});

httpServer.listen(HTTP_PORT, () => {
  console.log(`Health check on port ${HTTP_PORT}`);
});
