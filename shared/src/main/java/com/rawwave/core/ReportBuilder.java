package com.rawwave.core;

import java.io.File;
import java.io.IOException;

/** Builds a local verified diagnostic archive; knows nothing about Android or Downloads. */
public interface ReportBuilder {
    record Report(File file, int includedWatchReports) {}
    Report build(long operation) throws IOException;
}
