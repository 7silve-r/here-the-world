These small fixtures are generated test data, not user uploads.
Generated with FFmpeg 9.1 from lavfi color/testsrc/sine sources.

video.mp4: 0.4-second H.264/AAC video, 160x90.
silent.webm: 0.4-second VP9 video, 321x181, without audio.
audio.wav / audio.mp3 / audio.flac / audio.ogg / audio.m4a:
  0.4-second 440 Hz sine wave in the corresponding container.
cover.jpg / cover.mp3: synthetic blue cover and an MP3 with attached cover.
transparent.webp: transparent 32x16 lossless WebP image.

MediaEngineTest runs Java ImageIO and controlled process tests by default.
Enable real media tests with -Dhtw.media-tests=true.
Specify executable paths with -Dhtw.test.ffmpeg=... and -Dhtw.test.ffprobe=...
or supply FFMPEG / FFPROBE environment variables. Defaults use PATH.
When enabled, missing tools fail the suite instead of silently skipping.
