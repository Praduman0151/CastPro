"use strict";

// Browser fallback receiver: JPEG frames from a second low-resolution mirror
// on the phone plus 48 kHz stereo PCM. It works over plain HTTP in every
// modern browser, no WebCodecs/HTTPS requirement.
const canvas = document.getElementById("remoteCanvas");
const ctx = canvas.getContext("2d", { alpha: false, desynchronized: true });
const loadingOverlay = document.getElementById("loadingOverlay");
const hudFps = document.getElementById("hudFps");
const hudStatus = document.getElementById("hudStatus");
const btnPlay = document.getElementById("btnPlay");
const btnFullscreen = document.getElementById("btnFullscreen");

let ws = null;
let reconnectTimer = null;
let aspectRatio = 16 / 9;
let frameCount = 0;
let lastFpsTime = performance.now();
let audioContext = null;
let audioGain = null;
let nextAudioTime = 0;
let audioEnabled = false;
let pendingBlob = null;
let drawing = false;

function wsUrl() {
    const scheme = window.location.protocol === "https:" ? "wss" : "ws";
    const port = window.location.port
        ? Number(window.location.port) + 1
        : 8081;
    return `${scheme}://${window.location.hostname}:${port}`;
}

function resizeCanvas() {
    const ratio = window.innerWidth / window.innerHeight;
    let width;
    let height;
    if (ratio > aspectRatio) {
        height = window.innerHeight;
        width = height * aspectRatio;
    } else {
        width = window.innerWidth;
        height = width / aspectRatio;
    }
    canvas.style.width = `${Math.floor(width)}px`;
    canvas.style.height = `${Math.floor(height)}px`;
}

function applyGeometry(message) {
    if (message.aspectRatio && message.aspectRatio > 0.1) {
        aspectRatio = message.aspectRatio;
        resizeCanvas();
    }
    if (message.width > 0 && message.height > 0) {
        canvas.width = message.width;
        canvas.height = message.height;
    }
}

function drawJpeg(blob) {
    // Keep only the newest frame: if decoding is slow we want a current
    // picture, not a queue of stale ones.
    pendingBlob = blob;
    if (!drawing) drawNextFrame();
}

function drawNextFrame() {
    const blob = pendingBlob;
    if (!blob) {
        drawing = false;
        return;
    }
    pendingBlob = null;
    drawing = true;
    const url = URL.createObjectURL(blob);
    const image = new Image();
    image.onload = () => {
        if (canvas.width !== image.naturalWidth || canvas.height !== image.naturalHeight) {
            canvas.width = image.naturalWidth;
            canvas.height = image.naturalHeight;
        }
        ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
        URL.revokeObjectURL(url);
        loadingOverlay.style.display = "none";
        hudStatus.textContent = "STREAMING";
        hudStatus.className = "hud-val green";
        drawNextFrame();
    };
    image.onerror = () => {
        URL.revokeObjectURL(url);
        drawNextFrame();
    };
    image.src = url;
    frameCount++;
}

function ensureAudio() {
    if (!audioEnabled) return;
    if (!audioContext) {
        audioContext = new AudioContext({ latencyHint: "interactive" });
        audioGain = audioContext.createGain();
        audioGain.gain.value = 1.0;
        audioGain.connect(audioContext.destination);
        nextAudioTime = 0;
    }
    if (audioContext.state === "suspended") {
        audioContext.resume().catch(() => {});
    }
}

function playPcm(bytes) {
    if (!audioEnabled) return;
    ensureAudio();
    if (!audioContext || !audioGain) return;
    if (bytes.byteLength < 4) return;

    // 16-bit little-endian stereo interleaved PCM at 48 kHz.
    const samples = Math.floor(bytes.byteLength / 2);
    const frames = Math.floor(samples / 2);
    if (frames <= 0) return;
    const buffer = audioContext.createBuffer(2, frames, 48000);
    const left = buffer.getChannelData(0);
    const right = buffer.getChannelData(1);
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    for (let i = 0; i < frames; i++) {
        left[i] = view.getInt16(i * 4, true) / 32768;
        right[i] = view.getInt16(i * 4 + 2, true) / 32768;
    }

    const now = audioContext.currentTime;
    // A gap or a big backlog (tab was hidden) should not create minutes of
    // delayed audio; skip ahead instead.
    if (nextAudioTime < now + 0.02 || nextAudioTime > now + 0.4) {
        nextAudioTime = now + 0.06;
    }
    const source = audioContext.createBufferSource();
    source.buffer = buffer;
    source.connect(audioGain);
    source.start(nextAudioTime);
    nextAudioTime += buffer.duration;
}

function handlePacket(data) {
    if (!(data instanceof ArrayBuffer) || data.byteLength < 20) return;
    const view = new DataView(data);
    if (view.getUint8(0) !== 0x43 || view.getUint8(1) !== 0x50) return;
    const type = view.getUint8(2);
    const length = view.getUint32(16, false);
    if (length <= 0 || 20 + length > data.byteLength) return;
    const payload = data.slice(20, 20 + length);

    switch (type) {
        case 0x07: // TYPE_VIDEO_JPEG
            drawJpeg(new Blob([payload], { type: "image/jpeg" }));
            break;
        case 0x02: // TYPE_AUDIO_DATA
            playPcm(payload);
            break;
        default:
            break;
    }
}

function connectWebSocket() {
    if (ws && (ws.readyState === WebSocket.CONNECTING || ws.readyState === WebSocket.OPEN)) return;
    const url = wsUrl();
    console.log(`Connecting to Cast Pro WebSocket: ${url}`);
    ws = new WebSocket(url);
    ws.binaryType = "arraybuffer";

    ws.onopen = () => {
        console.log("Connected to Cast Pro stream server");
        hudStatus.textContent = "WAITING";
        hudStatus.className = "hud-val cyan";
    };

    ws.onmessage = (event) => {
        if (typeof event.data === "string") {
            try {
                const message = JSON.parse(event.data);
                if (message.type === "geometry") {
                    applyGeometry(message);
                }
            } catch (error) {
                console.warn("Bad text message", error);
            }
        } else {
            handlePacket(event.data);
        }
    };

    ws.onclose = () => {
        hudStatus.textContent = "DISCONNECTED";
        hudStatus.className = "hud-val red";
        scheduleReconnect();
    };

    ws.onerror = () => {
        if (ws) ws.close();
    };
}

function scheduleReconnect() {
    if (reconnectTimer) return;
    reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        connectWebSocket();
    }, 2000);
}

btnPlay.addEventListener("click", () => {
    audioEnabled = true;
    ensureAudio();
    btnPlay.style.display = "none";
});

btnFullscreen.addEventListener("click", () => {
    if (!document.fullscreenElement) {
        document.documentElement.requestFullscreen().catch(console.warn);
    } else {
        document.exitFullscreen().catch(console.warn);
    }
});

window.addEventListener("resize", resizeCanvas);
document.addEventListener("visibilitychange", () => {
    if (!document.hidden) {
        ensureAudio();
        nextAudioTime = 0;
    }
});

setInterval(() => {
    const now = performance.now();
    const elapsed = now - lastFpsTime;
    if (elapsed >= 1000) {
        hudFps.textContent = `${Math.round((frameCount * 1000) / elapsed)} FPS`;
        frameCount = 0;
        lastFpsTime = now;
    }
}, 500);

resizeCanvas();
connectWebSocket();
