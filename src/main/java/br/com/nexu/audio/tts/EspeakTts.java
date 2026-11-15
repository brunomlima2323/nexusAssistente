package br.com.nexu.audio.tts;

import java.util.List;

/** Linux: espeak-ng (ou espeak) com voz pt-br. */
public final class EspeakTts extends ProcessTts {

    private final String executable;
    private final String voice;
    private final int speed;

    public EspeakTts(String executable, String voice, int speed) {
        this.executable = executable;
        this.voice = voice;
        this.speed = speed;
    }

    @Override
    public String name() {
        return executable + " (" + voice + ")";
    }

    @Override
    protected List<String> command(String text) {
        return List.of(executable, "-v", voice, "-s", Integer.toString(speed), "--stdin");
    }

    @Override
    protected boolean textViaStdin() {
        return true;
    }
}
