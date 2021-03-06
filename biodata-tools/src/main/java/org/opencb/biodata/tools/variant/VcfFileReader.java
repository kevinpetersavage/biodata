package org.opencb.biodata.tools.variant;


import htsjdk.tribble.readers.LineIterator;
import htsjdk.tribble.readers.LineIteratorImpl;
import htsjdk.tribble.readers.LineReader;
import htsjdk.tribble.TribbleException;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFHeader;
import org.opencb.biodata.formats.variant.vcf4.FullVcfCodec;
import org.opencb.commons.io.DataReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * Created by joaquin on 9/27/16.
 */
public class VcfFileReader  implements DataReader<VariantContext> {

    private final Logger logger = LoggerFactory.getLogger(VcfFileReader.class);

    private String inputFilename;
    private InputStream inputStream;
    private FullVcfCodec codec;
    private VCFHeader header;
    private LineIterator lineIterator;
    private List<String> headerLines;
    private boolean lazy;
    private Set<BiConsumer<String, RuntimeException>> malformHandlerSet = new HashSet<>();

    public VcfFileReader registerMalformatedVcfHandler(BiConsumer<String, RuntimeException> handler) {
        this.malformHandlerSet.add(handler);
        return this;
    }

    public VcfFileReader(String inputFilename, boolean lazy) {
        this.inputFilename = inputFilename;
        this.lazy = lazy;
    }

    @Override
    public boolean open() {
        try {
            inputStream = new FileInputStream(new File(inputFilename));

            codec = new FullVcfCodec();
            lineIterator = codec.makeSourceFromStream(inputStream);

            // Read the header
            headerLines = new LinkedList<>();
            while (lineIterator.hasNext()) {
                String line = lineIterator.peek();
                if (line.startsWith(VCFHeader.HEADER_INDICATOR)) {
                    headerLines.add(line);
                    lineIterator.next();
                } else {
                    break;
                }
            }

            // Parse the header
            header = (VCFHeader) codec.readActualHeader(new LineIteratorImpl(new LineReader() {
                Iterator<String> iterator = headerLines.iterator();

                @Override
                public String readLine() throws IOException {
                    if (iterator.hasNext()) {
                        return iterator.next();
                    } else {
                        return null;
                    }
                }

                @Override
                public void close() {
                }
            }));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    @Override
    public List<VariantContext> read(int batchSize) {
        List<VariantContext> variantContexts = new ArrayList<>(batchSize);
        while (lineIterator.hasNext() && variantContexts.size() < batchSize) {
            String line = lineIterator.next();
            if (line.startsWith("#") || line.trim().isEmpty()) {
                continue;
            }
            try {
                VariantContext variantContext = codec.decode(line);
                // Lazy processing management
                if (!lazy && variantContext.getGenotypes().isLazyWithData()) {
                    variantContext.getGenotype(variantContext.getGenotypes().size() - 1);
                }
                variantContext.getGenotypes();
                variantContexts.add(variantContext);
            } catch (TribbleException e) {
                if (e.getMessage().startsWith("The provided VCF file is malformed at approximately line number")) {
                    logMalformatedLine(line, e);
                } else {
                    throw e;
                }
            }
        }
        return variantContexts;
    }

    private void logMalformatedLine(String line, RuntimeException error) {
        logger.warn(error.getMessage());
        for (BiConsumer<String, RuntimeException> consumer : this.malformHandlerSet) {
            consumer.accept(line, error);
        }
    }

    @Override
    public boolean close() {
        try {
            inputStream.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return true;
    }

    public VCFHeader getVcfHeader() {
        if (header == null) {
            open();
            close();
        }
        return header;
    }

    public boolean isLazy() {
        return lazy;
    }

    public VcfFileReader setLazy(boolean lazy) {
        this.lazy = lazy;
        return this;
    }
}
