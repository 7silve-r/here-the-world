// Original offline demo illustrations and synthesized sound. No external media.
import { mkdirSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
const dir = new URL("../public/media/", import.meta.url);
mkdirSync(dir, { recursive: true });
const common = `<defs><linearGradient id="sky" x2="0" y2="1"><stop stop-color="#b5c8bb"/><stop offset="1" stop-color="#e6dfc6"/></linearGradient><linearGradient id="sea" x2=".5" y2="1"><stop stop-color="#a2c3b7"/><stop offset="1" stop-color="#436e69"/></linearGradient><linearGradient id="sand" x2="0" y2="1"><stop stop-color="#d6b996"/><stop offset="1" stop-color="#9d7151"/></linearGradient><filter id="grain"><feTurbulence type="fractalNoise" baseFrequency=".7" numOctaves="3" stitchTiles="stitch"/><feColorMatrix type="saturate" values="0"/><feComponentTransfer><feFuncA type="linear" slope=".08"/></feComponentTransfer><feBlend in="SourceGraphic" mode="multiply"/></filter></defs>`;
let rand = 31;
const random = () => {
  rand = (Math.imul(rand, 1664525) + 1013904223) >>> 0;
  return rand / 4294967296;
};
const trees = Array.from({ length: 48 }, (_, i) => {
  const x = i * 28 - 50,
    y = 550 + random() * 180,
    h = 80 + random() * 250;
  return `<path d="M${x} ${y} l${h * 0.23} ${-h * 0.6} h${-h * 0.11} l${h * 0.16} ${-h * 0.4} l${h * 0.2} ${h * 0.4} h${-h * 0.1} l${h * 0.22} ${h * 0.6}Z" fill="${i % 2 ? "#344f42" : "#526a50"}"/>`;
}).join("");
const scenes = [
  `<rect width="1200" height="900" fill="url(#sky)"/><circle cx="920" cy="180" r="75" fill="#efe3ba" opacity=".8"/><path d="M0 500L220 210 360 360 570 130 810 410 1010 280 1200 450V900H0Z" fill="#a0b2a2"/><path d="M0 590L260 380 460 520 650 295 915 530 1200 405V900H0Z" fill="#748d77"/><path d="M0 720Q180 480 410 650T850 600T1200 620V900H0Z" fill="#657a57"/><path d="M0 900Q220 630 490 710T1200 630V900Z" fill="#8b9870"/>${trees}<path d="M470 900Q560 800 610 730T720 650Q670 750 670 900Z" fill="#c8c29d"/><path d="M630 760v-26m-6 9 6-7 7 9m-11 15 4-12 5 12" stroke="#383e33" stroke-width="4" fill="none"/>`,
  `<rect width="1200" height="900" fill="#e7c1a3"/><circle cx="820" cy="300" r="120" fill="#e8a66b"/><path d="M0 440Q220 370 440 470T900 420T1200 450V900H0Z" fill="#9da8a3"/><path d="M0 560Q300 450 600 530T1200 480V900H0Z" fill="url(#sea)"/><path d="M0 820Q400 520 820 700T1200 650V900H0Z" fill="#d3bd9a"/><path d="M0 870Q420 600 800 760T1200 710" fill="none" stroke="#eee2c5" stroke-width="18"/><path d="M700 620l100-8-30 22-50 0Z" fill="#414f46"/><path d="M755 610V470L680 602Z" fill="#eee6cb"/><path d="M763 608V510l53 88Z" fill="#d6ceb3"/>`,
  `<rect width="1200" height="900" fill="#c1cfcb"/><path d="M0 500Q250 300 420 520T840 450T1200 480V900H0Z" fill="#6f9692"/><path d="M0 580Q300 410 620 570T1200 550V900H0Z" fill="#417674"/><path d="M0 770Q300 590 640 700T1200 650V900H0Z" fill="#88aaa0"/><path d="M-50 720Q400 480 800 620T1300 620" fill="none" stroke="#d0dfc9" stroke-width="9" opacity=".7"/><path d="M0 820Q450 600 800 760T1200 700" fill="none" stroke="#c2d3b9" stroke-width="4"/>${Array.from({ length: 22 }, (_, i) => `<path d="M${random() * 1200} ${600 + random() * 300}h${10 + random() * 70}" stroke="#d0d8c1" opacity=".35" stroke-width="2"/>`).join("")}<path d="M0 0H180L260 170 180 350 210 500 90 670 0 640Z" fill="#394f43"/><path d="M0 0H90L140 170 95 350 120 500 0 640Z" fill="#62765b"/>`,
  `<rect width="1200" height="900" fill="#d7c8b1"/><path d="M0 0H780V900H0Z" fill="#b08768"/><path d="M780 0H1200V900H780Z" fill="#8c7760"/><path d="M100 0H650V900H100Z" fill="#cda989"/><path d="M250 250a150 150 0 0 1 300 0V900H250Z" fill="#496457"/><path d="M275 270a125 125 0 0 1 250 0V700H275Z" fill="#a3b6a1"/><path d="M275 550Q400 390 525 510V700H275Z" fill="#6f8767"/><path d="M0 900L450 600H900L1200 900Z" fill="#ddd1b5"/>${Array.from({ length: 8 }, (_, i) => `<path d="M${90 + i * 50} ${900 - i * 38}h${1020 - i * 90}v38H${90 + i * 50}Z" fill="${i % 2 ? "#b59b7b" : "#d1bc9b"}"/>`).join("")}<path d="M940 660V360M940 440q-130-100-130-210 130 20 130 210M940 520q150-100 145-230-140 35-145 230M940 360q100-100 55-200-100 90-55 200" stroke="#526a4e" fill="#526a4e" stroke-width="10"/><path d="M850 650H1040L1010 820H880Z" fill="#a35f41"/>`,
  `<rect width="1200" height="900" fill="#d6c5a8"/><circle cx="320" cy="230" r="110" fill="#dba272"/><path d="M0 530Q300 250 660 510T1200 420V900H0Z" fill="#bd8663"/><path d="M0 700Q400 310 800 610T1200 570V900H0Z" fill="#e0b183"/><path d="M0 900Q530 450 950 740T1300 620V900Z" fill="#9b6b50"/><path d="M100 900Q520 520 900 700" fill="none" stroke="#f0c79c" stroke-width="4"/><path d="M0 700Q400 310 800 610" fill="none" stroke="#efd0a6" stroke-width="4"/><path d="M730 653v-26m-5 8 5-8 6 8m-10 17 4-14 5 14" stroke="#45483b" stroke-width="4" fill="none"/>`,
  `<rect width="1200" height="900" fill="#b9c2ac"/><circle cx="900" cy="200" r="120" fill="#e0d5a3"/><path d="M0 700Q300 440 660 650T1200 520V900H0Z" fill="#79927a"/><path d="M0 850Q320 650 720 800T1200 680V900H0Z" fill="#9fa778"/><path d="M450 900Q550 740 750 690L800 685Q600 780 580 900Z" fill="#d3cba2"/>${Array.from(
    { length: 12 },
    (_, i) => {
      const x = i * 110 - 60,
        y = 220 + random() * 180;
      return `<path d="M${x} 900Q${x - 35} 550 ${x + 30} ${y}" stroke="#566b4a" stroke-width="${12 + random() * 18}" fill="none"/><ellipse cx="${x + 20}" cy="${y}" rx="${90 + random() * 80}" ry="${120 + random() * 80}" fill="${i % 3 === 0 ? "#4f7056" : i % 3 === 1 ? "#71865c" : "#8b9b69"}"/>`;
    },
  ).join("")}`,
];
scenes.forEach((scene, i) =>
  writeFileSync(
    new URL(`landscape-${i}.svg`, dir),
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 900" role="img" aria-label="原创风景插画 ${i + 1}">${common}<g filter="url(#grain)">${scene}</g></svg>`,
  ),
);
const rate = 22050,
  seconds = 6,
  count = rate * seconds,
  buffer = Buffer.alloc(44 + count * 2);
buffer.write("RIFF");
buffer.writeUInt32LE(buffer.length - 8, 4);
buffer.write("WAVEfmt ", 8);
buffer.writeUInt32LE(16, 16);
buffer.writeUInt16LE(1, 20);
buffer.writeUInt16LE(1, 22);
buffer.writeUInt32LE(rate, 24);
buffer.writeUInt32LE(rate * 2, 28);
buffer.writeUInt16LE(2, 32);
buffer.writeUInt16LE(16, 34);
buffer.write("data", 36);
buffer.writeUInt32LE(count * 2, 40);
for (let i = 0; i < count; i++) {
  const t = i / rate,
    envelope = Math.sin((Math.PI * t) / seconds) ** 2;
  const sound =
    (Math.sin(2 * Math.PI * 220 * t) * 0.12 +
      Math.sin(2 * Math.PI * 330 * t) * 0.08 +
      (random() - 0.5) * 0.04) *
    envelope;
  buffer.writeInt16LE(Math.round(sound * 32767), 44 + i * 2);
}
writeFileSync(new URL("field.wav", dir), buffer);
if (process.argv[2]) {
  const condition = "lt((X-450)*(X-450)+(Y-90)*(Y-90),2000)";
  const channel = (sky, sun, water) =>
    `if(${condition},${sun},if(gt(Y,200+8*sin(X/90+T/2)),${water}+4*sin(Y/6+T),${sky}))`;
  const filter = `format=rgb24,geq=r='${channel(182, 231, 91)}':g='${channel(201, 197, 133)}':b='${channel(185, 143, 126)}'`;
  execFileSync(
    process.argv[2],
    [
      "-y",
      "-f",
      "lavfi",
      "-i",
      "color=c=0xb6c9b9:s=640x360:d=6:r=24",
      "-vf",
      filter,
      "-an",
      "-c:v",
      "libvpx-vp9",
      "-b:v",
      "180k",
      new URL("moving.webm", dir).pathname.replace(/^\/([A-Z]:)/, "$1"),
    ],
    { stdio: "inherit" },
  );
}
