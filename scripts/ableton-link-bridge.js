#!/usr/bin/env node
/**
 * Minimal Ableton Link Bridge v1
 * - Receives UDP JSON from Java (default :19110)
 * - Sends UDP JSON ACK back to Java (default :19111)
 * - Optional: if `abletonlink` package is installed, tries to expose real peer count.
 */

const dgram = require('dgram');

const LISTEN_HOST = process.env.LINK_BRIDGE_HOST || '127.0.0.1';
const LISTEN_PORT = Number(process.env.LINK_BRIDGE_LISTEN_PORT || 19110);
const ACK_HOST = process.env.LINK_BRIDGE_ACK_HOST || '127.0.0.1';
const ACK_PORT = Number(process.env.LINK_BRIDGE_ACK_PORT || 19111);

let link = null;
let linkEnabled = false;
let linkError = '';

try {
  // Optional dependency. If unavailable, bridge still works in ACK mode.
  // eslint-disable-next-line import/no-extraneous-dependencies
  const AbletonLink = require('abletonlink');
  try {
    link = new AbletonLink(120);
    linkEnabled = true;
    console.log('[link-bridge] Ableton Link backend enabled');
  } catch (e) {
    linkError = `abletonlink init failed: ${e.message || e}`;
    console.warn(`[link-bridge] ${linkError}`);
  }
} catch {
  console.log('[link-bridge] abletonlink package not installed; running ACK-only mode');
}

const server = dgram.createSocket('udp4');
const ackSocket = dgram.createSocket('udp4');

let lastRxTs = 0;
let lastAckTs = 0;
let lastTempo = 120;
let lastBeatPosition = 0;
let lastPlaying = false;
let numPeers = 0;
let error = '';

function readNumPeers() {
  if (!linkEnabled || !link) return 0;
  try {
    if (typeof link.numPeers === 'function') return Number(link.numPeers()) || 0;
    if (typeof link.getNumPeers === 'function') return Number(link.getNumPeers()) || 0;
    if (typeof link.peers === 'number') return Number(link.peers) || 0;
  } catch (e) {
    error = `read peers failed: ${e.message || e}`;
  }
  return 0;
}

function applyToLink(payload) {
  if (!linkEnabled || !link) return;
  try {
    const tempo = Number(payload.tempo);
    const beatPosition = Number(payload.beatPosition);
    const playing = !!payload.playing;

    if (Number.isFinite(tempo) && tempo > 0) {
      if (typeof link.setTempo === 'function') link.setTempo(tempo);
      else if (typeof link.tempo === 'function') link.tempo(tempo);
      else if ('tempo' in link) link.tempo = tempo;
    }

    // Phase / beat alignment: best-effort (API differences across packages)
    if (Number.isFinite(beatPosition)) {
      if (typeof link.setBeat === 'function') link.setBeat(beatPosition);
      else if (typeof link.setBeatAtTime === 'function') link.setBeatAtTime(beatPosition, Date.now());
    }

    if (typeof link.setPlaying === 'function') link.setPlaying(playing);
  } catch (e) {
    error = `apply link failed: ${e.message || e}`;
  }
}

function sendAck() {
  const now = Date.now();
  lastAckTs = now;
  numPeers = readNumPeers();

  const ack = {
    type: 'ack',
    running: true,
    numPeers,
    lastAckTs,
    error: error || linkError || ''
  };

  const buf = Buffer.from(JSON.stringify(ack), 'utf8');
  ackSocket.send(buf, ACK_PORT, ACK_HOST, (err) => {
    if (err) {
      error = `ack send failed: ${err.message || err}`;
      console.error(`[link-bridge] ${error}`);
    }
  });
}

server.on('listening', () => {
  const addr = server.address();
  console.log(`[link-bridge] listening udp://${addr.address}:${addr.port}, ack=>udp://${ACK_HOST}:${ACK_PORT}`);
});

server.on('error', (err) => {
  console.error('[link-bridge] server error:', err);
});

server.on('message', (msg) => {
  const now = Date.now();
  lastRxTs = now;
  error = '';

  try {
    const payload = JSON.parse(msg.toString('utf8'));

    lastTempo = Number(payload.tempo) || lastTempo;
    lastBeatPosition = Number(payload.beatPosition) || lastBeatPosition;
    lastPlaying = !!payload.playing;

    applyToLink(payload);

    console.log(`[link-bridge] rx tempo=${lastTempo.toFixed(3)} beat=${lastBeatPosition.toFixed(3)} playing=${lastPlaying} rxTs=${lastRxTs}`);
    sendAck();
    console.log(`[link-bridge] ack lastAckTs=${lastAckTs} numPeers=${numPeers} error=${error || linkError || ''}`);
  } catch (e) {
    error = `json parse failed: ${e.message || e}`;
    console.error(`[link-bridge] ${error}`);
    sendAck();
  }
});

process.on('SIGINT', () => {
  console.log('[link-bridge] stopping...');
  try { server.close(); } catch {}
  try { ackSocket.close(); } catch {}
  process.exit(0);
});

server.bind(LISTEN_PORT, LISTEN_HOST);
