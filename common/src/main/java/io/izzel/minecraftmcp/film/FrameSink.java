package io.izzel.minecraftmcp.film;

import java.io.IOException;
import java.util.Map;

/** Where encoded frames go. Frames are GL-order RGBA (see {@link Frames}); calls come from one encoder thread, in order. */
public interface FrameSink {
    void write(byte[] rgba) throws IOException;

    /** Flushes and closes; returns fields for the report (format, path). */
    Map<String, Object> finish() throws IOException, InterruptedException;

    /** Stops without finishing, e.g. after an error. */
    void abort();
}
