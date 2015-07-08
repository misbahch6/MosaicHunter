package cn.edu.pku.cbi.mosaichunter.filter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sf.samtools.AlignmentBlock;
import net.sf.samtools.SAMRecord;
import cn.edu.pku.cbi.mosaichunter.Site;
import cn.edu.pku.cbi.mosaichunter.config.ConfigManager;

public class MisalignedReadsFilter extends BaseFilter {
    
    public static final String DEFAULT_BLAT_PARAM 
            = "-stepSize=5 -repMatch=2253 -minScore=0 -minIdentity=0.5 -noHead";
    public static final double DEFAULT_MAX_MISALIGNMENT_PERCENTAGE = 0.5;
    public static final int DEFAULT_MIN_SIDE_DISTANCE = 15;
    public static final int DEFAULT_MIN_GAP_DISTANCE = 5;
    public static final double DEFAULT_MIN_OVERLAP_PERCENTAGE = 0.9;
    public static final int DEFAULT_MAX_NM = 2;
    public static final boolean DEFAULT_ENABLE_BLAT = true;
        
    private final String blatPath;
    private final String blatParam;
    private final String outputDir;
    private final String tmpInputFaFile;
    private final String tmpOutputPslFile;
    private final String referenceFile;
    private final double maxMisalignmentPercentage;
    private final int minSideDistance;
    private final int minGapDistance;
    private final double minOverlapPercentage;
	private final int maxNM;
    private final boolean enableBlat;

    public MisalignedReadsFilter(String name) {
        this(name,
             ConfigManager.getInstance().get(name, "blat_path", null),
             ConfigManager.getInstance().get(name, "blat_param", DEFAULT_BLAT_PARAM),
             ConfigManager.getInstance().get(null, "output_dir", "."),
             ConfigManager.getInstance().get(name, "reference_file", "").isEmpty() ?
                     ConfigManager.getInstance().get(null, "reference_file", "") :
                     ConfigManager.getInstance().get(name, "reference_file", ""),
             ConfigManager.getInstance().getDouble(
                     name, "max_misalignment_percentage", DEFAULT_MAX_MISALIGNMENT_PERCENTAGE),
             ConfigManager.getInstance().getInt(
                      name, "min_side_distance", DEFAULT_MIN_SIDE_DISTANCE),
             ConfigManager.getInstance().getInt(
                      name, "min_gap_distance", DEFAULT_MIN_GAP_DISTANCE),
             ConfigManager.getInstance().getDouble(
                      name, "min_overlap_percentage", DEFAULT_MIN_OVERLAP_PERCENTAGE),
			 ConfigManager.getInstance().getInt(
                      name, "max_NM", DEFAULT_MAX_NM),
             ConfigManager.getInstance().getBoolean(
                name, "enable_blat", DEFAULT_ENABLE_BLAT));
    }
    
    public MisalignedReadsFilter(String name, 
            String blatPath, String blatParam, String outputDir, String referenceFile,
            double maxMisalignmentPercentage, int minSideDistance, int minGapDistance, 
            double minOverlapPercentage, int maxNM, boolean enableBlat) {
        super(name);
        this.blatPath = blatPath;
        this.blatParam = blatParam;        
        this.outputDir = outputDir;
        this.tmpInputFaFile = new File(outputDir, name + ".fa").getPath();
        this.tmpOutputPslFile = new File(outputDir, name + ".psl").getPath();
        this.referenceFile = referenceFile;
        this.maxMisalignmentPercentage = maxMisalignmentPercentage;
        this.minSideDistance = minSideDistance;
        this.minGapDistance = minGapDistance;
        this.minOverlapPercentage = minOverlapPercentage;
		this.maxNM = maxNM;
		this.enableBlat = enableBlat;
    }        
    
    @Override
    public boolean doFilter(Site site) {
        return !doFilter(Collections.singletonList(site)).isEmpty();
    }
    
    @Override
    public List<Site> doFilter(List<Site> sites) {
        //System.out.println(new Date() + " " + getName() + " " + sites.size());
        if (sites.isEmpty()) {
            return sites;
        }
        try {
            Map<String, AlignmentEntry> alignments = null;
            if (enableBlat) {
                createFastaFile(sites);
                int result = runBlat();
                if (result != 0) {
                    System.out.println("blat process failed(exit code: " + result + ")");
                    return new ArrayList<Site>();
                }
                alignments = parsePslFile();
            }
            
            List<Site> results = new ArrayList<Site>();
            
            for (Site entry : sites) {
                int misalignmentMajorCount = 0;
                int misalignmentMinorCount = 0;
                int[][] alignmentResultCount = new int[2][AlignmentResult.values().length];
                
                for (int i = 0; i < entry.getDepth(); ++i) {
                    byte base = entry.getBases()[i];
                    if (base != entry.getMajorAllele() && base != entry.getMinorAllele()) {
                        continue;
                    }
                    SAMRecord samRecord = entry.getReads()[i];
                    int readPos = entry.getBasePos()[i];
                    String id = samRecord.getReadName();
                    if (samRecord.getFirstOfPairFlag()) {
                        id += "/1";
                    } else {
                        id += "/2";
                    }
                    AlignmentEntry alignment = enableBlat ? alignments.get(id) : null;
                    AlignmentResult r = getAlignmentResult(
                            samRecord, entry.getRefName(), readPos, alignment);
                    if (r.equals(AlignmentResult.ALIGNMENT_MISSING) && !enableBlat) {
                        r = AlignmentResult.ALIGNMENT_OK;
                    }
                    
                    alignmentResultCount[base == entry.getMajorAllele() ? 0 : 1][r.ordinal()]++;
                    if (r != AlignmentResult.ALIGNMENT_OK) {
                        if (base == entry.getMajorAllele()) {
                            misalignmentMajorCount++;
                        } else {
                            misalignmentMinorCount++;
                        }
                    }
                }
                
                double p1 = (double) misalignmentMajorCount / entry.getMajorAlleleCount();
                double p2 = (double) misalignmentMinorCount / entry.getMinorAlleleCount();
                
                List<Object> metadata = new ArrayList<Object>();
                for (AlignmentResult r : AlignmentResult.values()) {
                    String d = r.name() + ":" + alignmentResultCount[0][r.ordinal()] + 
                                          "," + alignmentResultCount[1][r.ordinal()];
                    metadata.add(d);
                }
                metadata.add("misalignment:" + misalignmentMajorCount + 
                                         "," + misalignmentMinorCount);
                metadata.add(p1);
                metadata.add(p2);
                entry.setMetadata(
                        getName(),
                        metadata.toArray());
                
                if (p1 < maxMisalignmentPercentage + 1e-9 && p2 < maxMisalignmentPercentage + 1e-9) {
                    results.add(entry);
                }
            }
             
            return results;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<Site>(); 
        }
    }    
    
    private AlignmentResult getAlignmentResult(
            SAMRecord samRecord, String chr, int readPos, AlignmentEntry alignment) {
        if (readPos < minSideDistance || 
            samRecord.getReadLength() - 1 - readPos < minSideDistance) {
            return AlignmentResult.NEAR_SIDE;
        }
        Object nm = samRecord.getAttribute("NM");
        if (nm != null && nm instanceof Integer && (Integer) nm > maxNM) {
            return AlignmentResult.NM;
        }        
        
        for (AlignmentBlock ab : samRecord.getAlignmentBlocks()) {
            int pos = readPos +1;
            if ((pos >= ab.getReadStart() && pos - ab.getReadStart() < minGapDistance) ||
                (pos <= ab.getReadStart() + ab.getLength() - 1 
                 && ab.getReadStart() + ab.getLength() - 1 - pos < minGapDistance)) {
              return AlignmentResult.NEAR_GAP;
            }
        }
        
        if (alignment == null) {
            return AlignmentResult.ALIGNMENT_MISSING;
        }        
        if (alignment.count > 1) {
            return AlignmentResult.MULTIPLE_ALIGNMENTS;
        }
        if (!alignment.chr.equals(chr)) {
            return AlignmentResult.CHROM_MISMATCH;
        }
        
        String[] psl = alignment.psl;
        int blockCount = Integer.parseInt(psl[psl.length - 4]);
        int[] blockSizes = new int[blockCount];
        int[] blockPositions = new int[blockCount];
        String[] blockSizeTokens = psl[psl.length - 3].split(",");
        String[] blockPositionTokens = psl[psl.length - 1].split(",");
        for (int i = 0; i < blockCount; ++i) {
            blockSizes[i] = Integer.parseInt(blockSizeTokens[i]);
            blockPositions[i] = Integer.parseInt(blockPositionTokens[i]);
        }
      
        if (calcOverlap(blockSizes, blockPositions, samRecord) < minOverlapPercentage) {
            return AlignmentResult.ALIGNMENT_MISMATCH;
        } 
        return AlignmentResult.ALIGNMENT_OK;
    }
    
    private double calcOverlap(int[] blockSizes, int[] blockPositions, SAMRecord record) {
        
        int overlap = 0;
        int j = 0;
        for (int i = 1; i <= record.getReadLength() && j < blockSizes.length; ++i) {
            int refPos = record.getReferencePositionAtReadPosition(i);
            if (refPos >= blockPositions[j] && refPos < blockPositions[j] + blockSizes[j]) {
                overlap++;
            } else if (refPos >= blockPositions[j] + blockSizes[j]) {
                j++;
                i--;
            }
        }
        
        return (double) overlap / record.getReadLength();
    }
    
    private void createFastaFile(List<Site> sites) throws IOException {
        File dir = new File(outputDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        BufferedWriter writer = new BufferedWriter(new FileWriter(tmpInputFaFile));
        Set<String> done = new HashSet<String>();
        for (Site site : sites) {
            for (int i = 0; i < site.getDepth(); ++i) {
                SAMRecord samRecord = site.getReads()[i];         
                String id = samRecord.getReadName();
                if (samRecord.getFirstOfPairFlag()) {
                    id += "/1";
                } else {
                    id += "/2";
                }                
                if (AlignmentResult.ALIGNMENT_MISSING != 
                    getAlignmentResult(
                            samRecord, site.getRefName(), 
                            site.getBasePos()[i], null)) {
                    continue;
                }
  
                if (done.contains(id)) {
                    continue;
                }
                done.add(id);
                writer.write('>');
                writer.write(id);
                writer.newLine();
                writer.write(samRecord.getReadString());
                writer.newLine();                
            }
        }
        writer.close();
    }
    
    private String getBlatCmd() {
        String cmd = "blat";
        if (blatPath == null || blatPath.trim().isEmpty()) {
            return cmd;
        }
        File pathFile = new File(blatPath);
        if (pathFile.isDirectory()) {
            return new File(blatPath, "blat").getAbsolutePath();
        } else if (pathFile.isFile()) {
            return pathFile.getAbsolutePath();
        } else {
            return "blat";
        }
    }
    
    private int runBlat() throws IOException, InterruptedException {
        String blatCmd = getBlatCmd();
        String cmd = blatCmd + " " + blatParam + " " + 
                referenceFile + " " + tmpInputFaFile + " " + tmpOutputPslFile;
        System.out.println("run blat: " + cmd);
        Runtime rt = Runtime.getRuntime();
        Process blat = rt.exec(cmd);
        return blat.waitFor();                
    }
    
    private Map<String, AlignmentEntry> parsePslFile() throws IOException {
        Map<String, AlignmentEntry> alignments = new HashMap<String, AlignmentEntry>();
        BufferedReader reader = null; 
        try {
            reader = new BufferedReader(new FileReader(tmpOutputPslFile));
            for(;;) {
                 String line =  reader.readLine();
                 if (line == null) {
                     break;
                 }
                 String[] tokens = line.split("\\t");
                 
                 AlignmentEntry entry = new AlignmentEntry();
                 String id = tokens[9];
                 entry.chr = tokens[13];
                 if (entry.chr.startsWith("chr")) {
                     entry.chr = entry.chr.substring(3); 
                 }
                 entry.score = Integer.parseInt(tokens[0]) +
                               Integer.parseInt(tokens[2]) - 
                               Integer.parseInt(tokens[1]) - 
                               Integer.parseInt(tokens[4]) - 
                               Integer.parseInt(tokens[6]);
                 entry.count = 1;
                 entry.psl = tokens;
                 AlignmentEntry entry2 = alignments.get(id);
                 if (entry2 == null || entry.score > entry2.score) {
                     alignments.put(id, entry);    
                 } else if (entry.score == entry2.score) {
                     entry2.count++;
                 }
            }
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
        return alignments;
    }
    
    private class AlignmentEntry {
        private String chr;
        private int score;
        private int count;
        private String[] psl;
    }
    
    public enum AlignmentResult {
        ALIGNMENT_OK,
        ALIGNMENT_MISSING,
        MULTIPLE_ALIGNMENTS,
        CHROM_MISMATCH,
        ALIGNMENT_MISMATCH,
        NEAR_SIDE,
        NEAR_GAP,
        NM
    }
    
}
