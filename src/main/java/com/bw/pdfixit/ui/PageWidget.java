package com.bw.pdfixit.ui;

import com.bw.pdfixit.model.Page;

/**
 * Shows a preview image of a page. Scales automatically to current size.
 */
public class PageWidget extends ImageWidget {

    /**
     * Page Number used only until page is rendered.
     */
    private int pageNr;
    private Page page;

    public PageWidget(int pageNr) {
        this.pageNr = pageNr;
        this.setAlternativeText("# " + pageNr);

    }

    public Page getPage() {
        return page;
    }

    public void setPage(Page page) {
        if (page != this.page) {
            if (page != null) {
                pageNr = page.pageNb;
                this.setAlternativeText("# " + pageNr);
            }
            this.page = page;
            setScale(page.scale);
            setImage(page.image);
        }
    }

    public int getPageNumber() {
        return pageNr;
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Page ").append(pageNr).append(" selected:").append(isSelected());
        if (page != null && page.image != null)
            stringBuilder.append(" image ").append(page.image.getWidth()).append(" x ").append(page.image.getHeight());
        return stringBuilder.toString();
    }

}
