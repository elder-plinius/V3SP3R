import { WebSocketServer, WebSocket } from "ws";

/**
 * V3SP3R ↔ Mentra Glasses Bridge Server
 *
 * This server acts as a relay between:
 *   - Smart glasses (MentraOS, Even Realities, Vuzix, etc.)
 *   - V3SP3R Android app
 *
 * Architecture:
 *   [Glasses] → (WebSocket) → [This Bridge] → (WebSocket) → [V3SP3R App]
 *                                    ↕
 *                           [MentraOS Cloud API]
 *
 * The bridge translates glasses events (voice transcriptions, camera frames)
 * into the V3SP3R wire protocol and relays AI responses back to the glasses
 * for TTS playback and HUD display.
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
    | "STATUS_UPDATE";
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

function broadcast(targets: WebSocket[], message: GlassesMessage) {
  const payload = JSON.stringify(message);
  for (const ws of targets) {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(payload);
    }
  }
}

// ==================== Server ====================

const PORT = parseInt(process.env.PORT || "8089", 10);

const wss = new WebSocketServer({ port: PORT });

console.log(`V3SP3R Glasses Bridge running on port ${PORT}`);

wss.on("connection", (ws, req) => {
  const clientId = req.headers["x-vesper-client"] as string | undefined;
  const clientType = clientId === "v3sp3r-android" ? "vesper" : "unknown";

  const client: ConnectedClient = {
    ws,
    type: clientType,
    connectedAt: Date.now(),
  };
  clients.set(ws, client);

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

  // Send welcome
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
    // Glasses → V3SP3R: relay voice and camera to the Android app
    case "VOICE_TRANSCRIPTION":
    case "CAMERA_PHOTO":
    case "VOICE_COMMAND":
      // Auto-detect client type from message direction
      if (client.type === "unknown") {
        client.type = "glasses";
        console.log(`Client identified as glasses`);
      }
      broadcast(getVesperClients(), message);
      break;

    // V3SP3R → Glasses: relay AI responses back to glasses
    case "AI_RESPONSE":
    case "STATUS_UPDATE":
      if (client.type === "unknown") {
        client.type = "vesper";
        console.log(`Client identified as vesper`);
      }
      broadcast(getGlassesClients(), message);
      break;
  }
}

// ==================== MentraOS Integration ====================
// When MentraOS SDK is available, uncomment and configure:
//
// import { AppServer } from '@mentraos/sdk';
//
// class VesperGlassesBridge extends AppServer {
//   async onSession(session: any, sessionId: string, userId: string) {
//
//     // Voice → V3SP3R
//     session.events.onTranscription((data: any) => {
//       if (data.isFinal) {
//         broadcast(getVesperClients(), {
//           type: 'VOICE_TRANSCRIPTION',
//           text: data.text,
//           isFinal: true,
//           metadata: { source: 'mentra', sessionId }
//         });
//       }
//     });
//
//     // Camera → V3SP3R (triggered by voice command "what am I looking at?")
//     // const photo = await session.camera.requestPhoto({
//     //   metadata: { reason: 'vesper-vision' }
//     // });
//     // const imageBase64 = Buffer.from(photo.photoData).toString('base64');
//     // broadcast(getVesperClients(), {
//     //   type: 'CAMERA_PHOTO',
//     //   text: 'What am I looking at?',
//     //   imageBase64,
//     //   imageMimeType: photo.mimeType || 'image/jpeg'
//     // });
//
//     // V3SP3R → Glasses: listen for AI responses and speak them
//     // (This would be triggered by messages from getGlassesClients)
//     // session.audio.speak(responseText, { language: 'en-US' });
//     // session.layouts.showTextWall(displayText);
//   }
// }
//
// const mentraApp = new VesperGlassesBridge({ port: PORT });
// mentraApp.start();

// ==================== Health Check ====================

// Basic HTTP health check for deployment platforms
import { createServer } from "http";

const httpServer = createServer((req, res) => {
  if (req.url === "/health") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(
      JSON.stringify({
        status: "ok",
        glasses: getGlassesClients().length,
        vesper: getVesperClients().length,
        uptime: process.uptime(),
      })
    );
  } else {
    res.writeHead(404);
    res.end();
  }
});

const HTTP_PORT = parseInt(process.env.HTTP_PORT || "8090", 10);
httpServer.listen(HTTP_PORT, () => {
  console.log(`Health check on port ${HTTP_PORT}`);
});
