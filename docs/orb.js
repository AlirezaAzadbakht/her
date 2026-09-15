// Live thinking-orbs for the site, drawn with the same warm ink as the app.
// Geometry comes straight from the upstream engine (MIT, Jakub Antalik); only the colour is ours.
const ENGINE = "https://cdn.jsdelivr.net/npm/thinking-orbs@0.3.1/dist/engine.es.js";
const REDUCED_T = 0.6;
const GLOW_MIX = 0.55;

const reduceMotion = matchMedia("(prefers-reduced-motion: reduce)");
const darkScheme = matchMedia("(prefers-color-scheme: dark)");
const palette = { far: [0, 0, 0], near: [0, 0, 0], glow: [] };

const clamp = (v) => Math.max(0, Math.min(1, v));

function parseHex(value) {
  const hex = value.trim().replace("#", "");
  return [0, 2, 4].map((at) => parseInt(hex.slice(at, at + 2), 16));
}

function readPalette() {
  const style = getComputedStyle(document.documentElement);
  palette.far = parseHex(style.getPropertyValue("--orb-far"));
  palette.near = parseHex(style.getPropertyValue("--orb-near"));
  palette.glow = ["--coral", "--amber", "--rose"].map((name) => parseHex(style.getPropertyValue(name)));
}

// Strength blends faint far ink toward a strong ink tinted around the coral, amber, rose wheel.
function ink(strength, hue, alpha) {
  const n = palette.glow.length;
  const scaled = hue * n;
  const i = Math.min(n - 1, Math.floor(scaled));
  const j = (i + 1) % n;
  const f = scaled - Math.floor(scaled);
  const rgb = [0, 1, 2].map((c) => {
    const glow = palette.glow[i][c] + (palette.glow[j][c] - palette.glow[i][c]) * f;
    const strong = glow + (palette.near[c] - glow) * (1 - GLOW_MIX);
    return Math.round(palette.far[c] + (strong - palette.far[c]) * strength);
  });
  return `rgba(${rgb[0]},${rgb[1]},${rgb[2]},${alpha})`;
}

function hueAt(dx, dy, drift) {
  const u = Math.atan2(dy, dx) / (2 * Math.PI) + 0.5 + drift;
  return u - Math.floor(u);
}

class Orb {
  constructor(canvas, engine) {
    this.canvas = canvas;
    this.ctx = canvas.getContext("2d");
    this.design = Number(canvas.dataset.size || 64);
    this.display = Number(canvas.dataset.display || this.design);
    this.still = canvas.hasAttribute("data-still");
    this.preset = engine.resolvePreset(canvas.dataset.state || "breathing", this.design);
    this.frame = engine.MODE_FRAMES[this.preset.mode];
    this.visible = true;
    this.drawn = false;
    const ratio = Math.min(2, window.devicePixelRatio || 1);
    canvas.width = Math.round(this.display * ratio);
    canvas.height = Math.round(this.display * ratio);
    canvas.style.width = `${this.display}px`;
    canvas.style.height = `${this.display}px`;
    this.zoom = canvas.width / this.design;
  }

  draw(seconds) {
    const frozen = this.still || reduceMotion.matches;
    const t = (frozen ? REDUCED_T : seconds) * this.preset.speed;
    const { dots, lines } = this.frame(this.design, t, this.preset.opts);
    const { ctx, zoom } = this;
    const center = this.design / 2;
    const drift = frozen ? 0 : seconds * 0.03;
    ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
    for (const l of lines) {
      ctx.strokeStyle = ink(1 - clamp(l.white), hueAt((l.x1 + l.x2) / 2 - center, (l.y1 + l.y2) / 2 - center, drift), clamp(l.a ?? 1));
      ctx.lineWidth = l.w * zoom;
      ctx.beginPath();
      ctx.moveTo(l.x1 * zoom, l.y1 * zoom);
      ctx.lineTo(l.x2 * zoom, l.y2 * zoom);
      ctx.stroke();
    }
    for (const d of dots) {
      ctx.fillStyle = ink(1 - clamp(d.white), hueAt(d.x - center, d.y - center, drift), clamp(d.a ?? 1));
      ctx.beginPath();
      ctx.arc(d.x * zoom, d.y * zoom, d.r * zoom, 0, Math.PI * 2);
      ctx.fill();
    }
    this.drawn = true;
  }
}

function revealOnScroll() {
  const items = document.querySelectorAll(".reveal");
  if (!("IntersectionObserver" in window) || reduceMotion.matches) return;
  document.documentElement.classList.add("reveal-ready");
  const observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        entry.target.classList.add("in");
        observer.unobserve(entry.target);
      }
    },
    { rootMargin: "0px 0px -8% 0px" },
  );
  items.forEach((item) => observer.observe(item));
}

async function startOrbs() {
  const canvases = [...document.querySelectorAll("canvas.orb")];
  if (canvases.length === 0) return;
  let engine;
  try {
    engine = await import(ENGINE);
  } catch {
    // Offline or blocked CDN: the halos and copy still carry the page.
    return;
  }
  readPalette();
  const orbs = canvases.map((canvas) => new Orb(canvas, engine));

  // Only animate orbs on screen; the browser already pauses frames in hidden tabs.
  const observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      const orb = orbs.find((o) => o.canvas === entry.target);
      if (orb) orb.visible = entry.isIntersecting;
    }
  });
  orbs.forEach((orb) => observer.observe(orb.canvas));

  const origin = performance.now();
  let running = false;
  const tick = (now) => {
    if (reduceMotion.matches) {
      running = false;
      return;
    }
    const seconds = (now - origin) / 1000;
    for (const orb of orbs) {
      if (orb.visible && !(orb.still && orb.drawn)) orb.draw(seconds);
    }
    requestAnimationFrame(tick);
  };
  const play = () => {
    if (running) return;
    running = true;
    requestAnimationFrame(tick);
  };
  const drawStill = () => orbs.forEach((orb) => orb.draw(0));

  darkScheme.addEventListener("change", () => {
    readPalette();
    drawStill();
  });
  reduceMotion.addEventListener("change", () => (reduceMotion.matches ? drawStill() : play()));
  if (reduceMotion.matches) drawStill();
  else play();
}

revealOnScroll();
startOrbs();
