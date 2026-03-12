import WebSocket from "ws";

/**
 * Glasses Adapter — connects any smart glasses SDK to the V3SP3R bridge.
 *
 * This file provides adapter patterns for different glasses platforms.
 * Each adapter translates the platform's native events into V3SP3R's
 * GlassesMessage wire protocol.
 *
 * Supported (planned):
 *   - MentraOS (Mentra Live, Even Realities G1)
 *   - Vuzix SDK (Vuzix Z100, Shield)
 *   - Xreal SDK (Xreal Air)
 *   - Generic WebRTC/WebSocket glasses
 */

interface GlassesMessage {
  type: string;
  text?: string;
  imageBase64?: string;
  imageMimeType?: string;
  displayText?: string;
  isFinal?: boolean;
  metadata?: Record<string, string>;
}

// ==================== Base Adapter ====================

abstract class GlassesAdapter {
  protected ws: WebSocket | null = null;
  protected bridgeUrl: string;

  constructor(bridgeUrl: string) {
    this.bridgeUrl = bridgeUrl;
  }

  connect() {
    this.ws = new WebSocket(this.bridgeUrl);

    this.ws.on("open", () => {
      console.log(`[${this.name}] Connected to bridge`);
      this.onBridgeConnected();
    });

    this.ws.on("message", (data) => {
      try {
        const message: GlassesMessage = JSON.parse(data.toString());
        this.onBridgeMessage(message);
      } catch (e) {
        console.warn(`[${this.name}] Invalid bridge message`);
      }
    });

    this.ws.on("close", () => {
      console.log(`[${this.name}] Disconnected from bridge`);
      // Auto-reconnect after 3 seconds
      setTimeout(() => this.connect(), 3000);
    });
  }

  protected send(message: GlassesMessage) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(message));
    }
  }

  abstract get name(): string;
  abstract onBridgeConnected(): void;
  abstract onBridgeMessage(message: GlassesMessage): void;
}

// ==================== MentraOS Adapter ====================

/**
 * MentraOS adapter for Mentra Live glasses.
 *
 * When MentraOS SDK is available, this adapter:
 * 1. Receives voice transcriptions from glasses mic
 * 2. Can request camera photos for vision analysis
 * 3. Speaks AI responses through glasses speakers
 * 4. Shows status on the glasses HUD display
 *
 * Usage:
 *   const adapter = new MentraAdapter('ws://bridge-url:8089');
 *   adapter.connect();
 *   // Then initialize MentraOS session and call adapter methods
 */
export class MentraAdapter extends GlassesAdapter {
  // MentraOS session reference (set when onSession fires)
  private session: any = null;

  get name() {
    return "MentraOS";
  }

  /**
   * Call this from your MentraOS AppServer.onSession handler.
   */
  initSession(session: any) {
    this.session = session;

    // Wire up voice transcription
    if (session.events?.onTranscription) {
      session.events.onTranscription(
        (data: { text: string; isFinal: boolean }) => {
          if (data.isFinal && data.text.trim()) {
            this.send({
              type: "VOICE_TRANSCRIPTION",
              text: data.text.trim(),
              isFinal: true,
              metadata: { source: "mentra" },
            });
          }
        }
      );
    }
  }

  /**
   * Request a photo from glasses camera and send to V3SP3R for vision analysis.
   */
  async requestVisionAnalysis(prompt: string = "What am I looking at?") {
    if (!this.session?.camera?.requestPhoto) {
      console.warn("[MentraOS] Camera not available");
      return;
    }

    try {
      const photo = await this.session.camera.requestPhoto({
        metadata: { reason: "vesper-vision" },
      });
      const imageBase64 = Buffer.from(photo.photoData).toString("base64");

      this.send({
        type: "CAMERA_PHOTO",
        text: prompt,
        imageBase64,
        imageMimeType: photo.mimeType || "image/jpeg",
        metadata: { source: "mentra" },
      });
    } catch (e) {
      console.error("[MentraOS] Camera capture failed:", (e as Error).message);
    }
  }

  onBridgeConnected() {
    // Nothing special needed
  }

  /**
   * Handle messages from V3SP3R (AI responses, status updates).
   * Speaks them through glasses and shows on HUD.
   */
  onBridgeMessage(message: GlassesMessage) {
    switch (message.type) {
      case "AI_RESPONSE":
        // Speak through glasses speakers
        if (this.session?.audio?.speak && message.text) {
          this.session.audio.speak(message.text, { language: "en-US" });
        }
        // Show on HUD
        if (this.session?.layouts?.showTextWall && message.displayText) {
          this.session.layouts.showTextWall(message.displayText);
        }
        break;

      case "STATUS_UPDATE":
        // Show brief notification on HUD
        if (this.session?.layouts?.showTextWall && message.text) {
          this.session.layouts.showTextWall(message.text);
        }
        break;
    }
  }
}

// ==================== Generic WebSocket Glasses Adapter ====================

/**
 * Generic adapter for glasses that expose a simple WebSocket API.
 * Works with any glasses that can send voice/camera and receive TTS/display commands.
 */
export class GenericGlassesAdapter extends GlassesAdapter {
  private glassesWs: WebSocket | null = null;

  get name() {
    return "Generic";
  }

  /**
   * Connect to a glasses device's local WebSocket server.
   */
  connectToGlasses(glassesWsUrl: string) {
    this.glassesWs = new WebSocket(glassesWsUrl);

    this.glassesWs.on("message", (data) => {
      try {
        const message: GlassesMessage = JSON.parse(data.toString());
        // Forward glasses events to the bridge
        this.send(message);
      } catch (_) {}
    });
  }

  onBridgeConnected() {}

  onBridgeMessage(message: GlassesMessage) {
    // Forward bridge responses to the glasses
    if (
      this.glassesWs?.readyState === WebSocket.OPEN &&
      (message.type === "AI_RESPONSE" || message.type === "STATUS_UPDATE")
    ) {
      this.glassesWs.send(JSON.stringify(message));
    }
  }
}
