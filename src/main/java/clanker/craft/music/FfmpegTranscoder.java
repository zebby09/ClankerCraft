package clanker.craft.music;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FfmpegTranscoder {
    private FfmpegTranscoder() {}

    /**
     * Transcodes input audio (wav/mp3/etc.) to OGG Vorbis 44.1kHz stereo using ffmpeg on PATH.
     * Throws IOException if ffmpeg fails or is not available.
     */
    public static void toOggVorbis(Path inputAudio, Path outputOgg) throws IOException, InterruptedException {
        if (inputAudio == null || outputOgg == null) throw new IllegalArgumentException("null path");
        if (!Files.exists(inputAudio)) throw new IOException("Input audio not found: " + inputAudio);
        Files.createDirectories(outputOgg.getParent());
        Process p = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", inputAudio.toAbsolutePath().toString(),
                "-ac", "2",
                "-ar", "44100",
                "-c:a", "libvorbis",
                outputOgg.toAbsolutePath().toString()
        ).redirectErrorStream(true).start();
        int code = p.waitFor();
        if (code != 0) throw new IOException("ffmpeg exited with code " + code);
    }
}

