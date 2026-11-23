let aspectRatio = 1.777;
let ws = null;
let reconnectTimer = null;
let frameCount = 0;
let lastFpsTime = Date.now();

const video = document.getElementById('remoteVideo');
const audio = document.getElementById('remoteAudio');
const loadingOverlay = document.getElementById('loadingOverlay');
const hudLatency = document.getElementById('hudLatency');
const hudFps = document.getElementById('hudFps');
const hudStatus = document.getElementById('hudStatus');
const btnPlay = document.getElementById('btnPlay');
const btnFullscreen = document.getElementById('btnFullscreen');

function updateAspectRatio(ratio) {
    aspectRatio = ratio;
    const windowRatio = window.innerWidth / window.innerHeight;
    if (windowRatio > ratio) {
        video.style.width = `${window.innerHeight * ratio}px`;
        video.style.height = '100%';
    } else {
        video.style.width = '100%';
        video.style.height = `${window.innerWidth / ratio}px`;
    }
}

window.addEventListener('resize', () => updateAspectRatio(aspectRatio));

btnPlay.addEventListener('click', () => {
    video.muted = false;
    audio.muted = false;
    video.play().catch(console.warn);
    audio.play().catch(console.warn);
    btnPlay.style.display = 'none';
});

btnFullscreen.addEventListener('click', () => {
    if (!document.fullscreenElement) {
        document.documentElement.requestFullscreen().catch(console.warn);
    } else {
        document.exitFullscreen().catch(console.warn);
    }
});

function connectWebSocket() {
    if (ws && (ws.readyState === WebSocket.CONNECTING || ws.readyState === WebSocket.OPEN)) return;

    const host = window.location.hostname || "127.0.0.1";
    const wsUrl = `ws://${host}:8081`;
    console.log(`Connecting to Cast Pro WebSocket: ${wsUrl}`);

    ws = new WebSocket(wsUrl);
    ws.binaryType = "arraybuffer";

    ws.onopen = () => {
        console.log("Connected to Cast Pro Stream Server");
        loadingOverlay.style.display = 'none';
        hudStatus.textContent = "STREAMING";
        hudStatus.className = "hud-val green";
    };

    ws.onmessage = (event) => {
        if (typeof event.data === "string") {
            try {
                const msg = JSON.parse(event.data);
                if (msg.type === "geometry" && msg.aspectRatio) {
                    console.log("Geometry update received:", msg);
                    updateAspectRatio(msg.aspectRatio);
                } else if (msg.type === "heartbeat") {
                    const now = Date.now();
                    const latency = now - (msg.timestamp || now);
                    hudLatency.textContent = `${Math.max(12, latency)} ms`;
                }
            } catch (e) {
                console.error("JSON parse error", e);
            }
        } else if (event.data instanceof ArrayBuffer) {
            frameCount++;
            const now = Date.now();
            if (now - lastFpsTime >= 1000) {
                hudFps.textContent = `${frameCount} FPS`;
                frameCount = 0;
                lastFpsTime = now;
            }
        }
    };

    ws.onclose = () => {
        console.warn("WebSocket closed. Scheduling reconnect...");
        loadingOverlay.style.display = 'flex';
        hudStatus.textContent = "DISCONNECTED";
        hudStatus.className = "hud-val red";
        scheduleReconnect();
    };

    ws.onerror = (err) => {
        console.error("WebSocket error:", err);
        ws.close();
    };
}

function scheduleReconnect() {
    if (reconnectTimer) return;
    reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        connectWebSocket();
    }, 2000);
}

// Initialise
updateAspectRatio(aspectRatio);
connectWebSocket();
