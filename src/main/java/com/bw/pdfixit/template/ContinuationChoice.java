package com.bw.pdfixit.template;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The answer to the question "The invoice needs more than one page, the template has one": where do the following pages
 * come from. No UI involved, the dialog belongs to the wizard.
 */
public final class ContinuationChoice {

    private ContinuationChoice() {
    }

    /**
     * Sets the source of the following pages in the layout (in memory).
     *
     * @param source {@link Layout.Continuation#PAGE1}, {@link Layout.Continuation#BLANK} or
     *               {@link Layout.Continuation#PDF}.
     * @param pdf    The PDF whose first page is used, only for {@link Layout.Continuation#PDF}.
     */
    public static void apply(Layout layout, Layout.Continuation source, Path pdf) {
        if (source == Layout.Continuation.PDF && pdf == null)
            throw new IllegalArgumentException("A PDF is needed");
        layout.continuation = source;
        layout.continuationFile = source == Layout.Continuation.PDF ? pdf.toAbsolutePath().normalize() : null;
    }

    /**
     * Sets the source and stores the layout of the template, so the question is not asked again.
     *
     * @param template The template PDF the layout belongs to.
     * @return The layout file that was written.
     */
    public static Path applyAndRemember(Layout layout, Layout.Continuation source, Path pdf, Path template)
            throws IOException {
        apply(layout, source, pdf);
        return LayoutFiles.save(layout, template);
    }
}
