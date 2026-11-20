// Rebuild the original synthesized audio and the six-second character-card clip.
// Usage: node scripts/generate-party-media.mjs [path-to-ffmpeg]
import { mkdirSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";

const mediaRoot = new URL("../public/media/", import.meta.url);
const output = new URL("demo/", mediaRoot);
mkdirSync(output, { recursive: true });
const rate = 22050,
  seconds = 6,
  count = rate * seconds;
const wav = Buffer.alloc(44 + count * 2);
wav.write("RIFF");
wav.writeUInt32LE(wav.length - 8, 4);
wav.write("WAVEfmt ", 8);
wav.writeUInt32LE(16, 16);
wav.writeUInt16LE(1, 20);
wav.writeUInt16LE(1, 22);
wav.writeUInt32LE(rate, 24);
wav.writeUInt32LE(rate * 2, 28);
wav.writeUInt16LE(2, 32);
wav.writeUInt16LE(16, 34);
wav.write("data", 36);
wav.writeUInt32LE(count * 2, 40);
const melody = [
  523.25, 659.25, 783.99, 987.77, 783.99, 659.25, 587.33, 783.99, 659.25,
  523.25, 783.99, 1046.5, 987.77, 783.99, 659.25, 523.25,
];
for (let i = 0; i < count; i++) {
  const t = i / rate;
  let sample =
    Math.sin(2 * Math.PI * 261.63 * t) * 0.025 +
    Math.sin(2 * Math.PI * 392 * t) * 0.02;
  const note = Math.floor(t / 0.375);
  for (let n = Math.max(0, note - 2); n <= note; n++) {
    const age = t - n * 0.375,
      frequency = melody[n % melody.length];
    sample +=
      (Math.sin(2 * Math.PI * frequency * age) +
        0.18 * Math.sin(4 * Math.PI * frequency * age)) *
      (1 - Math.exp(-age * 100)) *
      Math.exp(-age * 5) *
      0.12;
  }
  const fade = Math.min(1, t * 5, (seconds - t) * 3);
  wav.writeInt16LE(
    Math.round(Math.max(-1, Math.min(1, sample * fade)) * 32767),
    44 + i * 2,
  );
}
writeFileSync(new URL("tabletop-chimes.wav", output), wav);

if (process.argv[2]) {
  const args = [
    "-y",
    "-f",
    "lavfi",
    "-i",
    "color=c=0xfff2d6:s=640x360:d=6:r=24",
  ];
  for (const name of ["qp", "suguri", "marc"])
    args.push(
      "-loop",
      "1",
      "-framerate",
      "24",
      "-i",
      fileURLToPath(new URL(`orange-juice/${name}.png`, mediaRoot)),
    );
  const filter = [
    "[0:v]drawgrid=w=40:h=40:t=1:c=0xe6c793@0.3,drawbox=x=15:y=15:w=610:h=330:c=0xe4c391:t=2[board]",
    "[1:v]scale=160:160,format=rgba[qp]",
    "[2:v]scale=160:160,format=rgba[suguri]",
    "[3:v]scale=160:160,format=rgba[marc]",
    "[board][qp]overlay=x='48+10*sin(t*1.2)':y='98+12*sin(t*1.5)'[first]",
    "[first][suguri]overlay=x='240+8*sin(t*1.1)':y='72+12*sin(t*1.5+2)'[second]",
    "[second][marc]overlay=x='432+8*sin(t*1.3)':y='106+12*sin(t*1.5+4)'[out]",
  ].join(";");
  args.push(
    "-filter_complex",
    filter,
    "-map",
    "[out]",
    "-t",
    "6",
    "-an",
    "-c:v",
    "libvpx-vp9",
    "-b:v",
    "260k",
    "-pix_fmt",
    "yuv420p",
    fileURLToPath(new URL("party-loop.webm", output)),
  );
  execFileSync(process.argv[2], args, { stdio: "inherit" });
}
