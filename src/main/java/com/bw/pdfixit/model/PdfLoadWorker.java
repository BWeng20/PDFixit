package com.bw.pdfixit.model;

public interface PdfLoadWorker {

    void execute();

    void cancel();

    boolean isFinished();

}
