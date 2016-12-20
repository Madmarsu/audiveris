//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                     K e y E x t r a c t o r                                    //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Hervé Bitteur and others 2000-2016. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package omr.sheet.header;

import omr.classifier.Classifier;
import omr.classifier.Evaluation;
import omr.classifier.GlyphClassifier;
import omr.classifier.SampleRepository;
import omr.classifier.SampleSheet;

import omr.constant.Constant;
import omr.constant.ConstantSet;

import omr.glyph.Glyph;
import omr.glyph.GlyphCluster;
import omr.glyph.GlyphFactory;
import omr.glyph.GlyphLink;
import omr.glyph.Glyphs;
import omr.glyph.Grades;
import omr.glyph.Shape;
import omr.glyph.Symbol.Group;

import omr.math.GeoUtil;
import static omr.run.Orientation.VERTICAL;
import omr.run.RunTable;
import omr.run.RunTableFactory;

import omr.sheet.Book;
import omr.sheet.Picture;
import omr.sheet.Scale;
import omr.sheet.Sheet;
import omr.sheet.Staff;
import omr.sheet.SystemInfo;

import omr.sig.SIGraph;
import omr.sig.inter.Inter;
import omr.sig.inter.KeyAlterInter;

import ij.process.ByteProcessor;

import org.jgrapht.alg.ConnectivityInspector;
import org.jgrapht.graph.SimpleGraph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Class {@code KeyExtractor} is a companion of KeyBuilder, focused on extracting key
 * alter glyphs from staff-free pixel source and recognizing them.
 *
 * @author Hervé Bitteur
 */
public class KeyExtractor
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(KeyExtractor.class);

    //~ Instance fields ----------------------------------------------------------------------------
    private final Sheet sheet;

    private final SystemInfo system;

    private final SIGraph sig;

    private final Staff staff;

    private final StaffHeader.Range range; // See KeyBuilder

    private final List<KeyPeak> peaks; // See KeyBuilder

    private final KeyRoi roi; // See KeyBuilder

    private final int id; // Staff ID

    /** Scale-dependent parameters. */
    private final Parameters params;

    /** Staff-free pixel source. */
    private final ByteProcessor staffFreeSource;

    /** Shape classifier to use. */
    private final Classifier classifier = GlyphClassifier.getInstance();

    /** All glyphs submitted to classifier. */
    private final Set<Glyph> glyphCandidates = new HashSet<Glyph>();

    //~ Constructors -------------------------------------------------------------------------------
    /**
     * Creates a new {@code KeyExtractor} object.
     *
     * @param staff the underlying staff
     * @param range key range information
     * @param peaks detected peaks
     * @param roi   roi with slices
     */
    public KeyExtractor (Staff staff,
                         StaffHeader.Range range,
                         List<KeyPeak> peaks,
                         KeyRoi roi)
    {
        this.staff = staff;
        this.range = range;
        this.peaks = peaks;
        this.roi = roi;

        system = staff.getSystem();
        sig = system.getSig();
        sheet = system.getSheet();
        id = staff.getId();
        params = new Parameters(sheet.getScale());

        staffFreeSource = sheet.getPicture().getSource(Picture.SourceKey.NO_STAFF);
    }

    //~ Methods ------------------------------------------------------------------------------------
    //--------------//
    // extractAlter //
    //--------------//
    /**
     * In the provided slice, extract the relevant foreground pixels from the NO_STAFF
     * image and evaluate possible glyph instances.
     *
     * @param slice         the slice to process
     * @param targetShapes  the set of shapes to try
     * @param minGrade      minimum acceptable grade
     * @param cropNeighbors true for discarding pixels from neighbors
     * @return the Inter created if any
     */
    public KeyAlterInter extractAlter (KeySlice slice,
                                       Set<Shape> targetShapes,
                                       double minGrade,
                                       boolean cropNeighbors)
    {
        Rectangle sliceRect = slice.getRect();
        ByteProcessor sliceBuf = roi.getSlicePixels(staffFreeSource, slice, cropNeighbors);
        RunTable runTable = new RunTableFactory(VERTICAL).createTable(sliceBuf);
        List<Glyph> parts = GlyphFactory.buildGlyphs(runTable, sliceRect.getLocation());
        purgeParts(parts, (sliceRect.x + sliceRect.width) - 1);
        system.registerGlyphs(parts, Group.ALTER_PART);

        SingleAdapter adapter = new SingleAdapter(slice, parts, targetShapes, minGrade);
        new GlyphCluster(adapter, null).decompose();

        if (slice.eval != null) {
            double grade = Inter.intrinsicRatio * slice.eval.grade;

            if (grade >= minGrade) {
                if ((slice.alter == null) || (slice.alter.getGlyph() != slice.glyph)) {
                    logger.debug("Glyph#{} {}", slice.glyph.getId(), slice.eval);

                    KeyAlterInter alterInter = KeyAlterInter.create(
                            slice.glyph,
                            slice.eval.shape,
                            grade,
                            staff);
                    sig.addVertex(alterInter);
                    slice.alter = alterInter;
                    logger.debug("{}", slice);
                }

                return slice.alter;
            }
        }

        return null;
    }

    //---------------//
    // recordSamples //
    //---------------//
    /**
     * Record glyphs used in key building as training samples.
     *
     * @param recordPositives true to record positive glyphs
     * @param recordNegatives true to retrieve negative glyphs
     * @param keyShape        key shape (SHARP or FLAT)
     */
    public void recordSamples (boolean recordPositives,
                               boolean recordNegatives,
                               Shape keyShape)
    {
        final Book book = sheet.getStub().getBook();
        final SampleRepository repository = book.getSampleRepository();

        if (repository == null) {
            return;
        }

        final SampleSheet sampleSheet = repository.findSampleSheet(sheet);
        final int interline = staff.getSpecificInterline();

        // Positive samples (assigned to keyShape)
        for (KeySlice slice : roi) {
            KeyAlterInter alter = slice.getAlter();

            if (alter != null) {
                final Glyph glyph = alter.getGlyph();

                if (recordPositives) {
                    final double pitch = staff.pitchPositionOf(glyph.getCentroid());
                    repository.addSample(keyShape, glyph, interline, sampleSheet, pitch);
                }

                glyphCandidates.remove(glyph);
            }
        }

        if (recordNegatives) {
            // Negative samples (assigned to CLUTTER)
            for (Glyph glyph : glyphCandidates) {
                final double pitch = staff.pitchPositionOf(glyph.getCentroid());
                repository.addSample(Shape.CLUTTER, glyph, interline, sampleSheet, pitch);
            }
        }
    }

    //--------------------//
    // retrieveCandidates //
    //--------------------//
    /**
     * Retrieve all possible candidates (as connected components) with acceptable shape.
     *
     * @param shapes acceptable shapes
     * @return the candidates found
     */
    public List<Candidate> retrieveCandidates (Set<Shape> shapes)
    {
        logger.debug("retrieveCandidates for staff#{}", id);

        // Key-signature area pixels
        ByteProcessor keyBuf = roi.getAreaPixels(staffFreeSource, range);
        RunTable runTable = new RunTableFactory(VERTICAL).createTable(keyBuf);
        List<Glyph> parts = GlyphFactory.buildGlyphs(
                runTable,
                new Point(range.getStart(), roi.y));

        purgeParts(parts, range.getStop());
        system.registerGlyphs(parts, Group.ALTER_PART);

        // Formalize parts relationships in a global graph
        SimpleGraph<Glyph, GlyphLink> globalGraph = Glyphs.buildLinks(parts, params.maxPartGap);
        List<Set<Glyph>> sets = new ConnectivityInspector<Glyph, GlyphLink>(
                globalGraph).connectedSets();
        logger.debug("Staff#{} sets:{}", id, sets.size());

        List<Candidate> allCandidates = new ArrayList<Candidate>();

        for (Set<Glyph> set : sets) {
            // Use only the subgraph for this set
            SimpleGraph<Glyph, GlyphLink> subGraph = GlyphCluster.getSubGraph(set, globalGraph);
            MultipleAdapter adapter = new MultipleAdapter(
                    subGraph,
                    shapes,
                    Grades.keyAlterMinGrade1);
            new GlyphCluster(adapter, null).decompose();
            logger.debug("Staff#{} set:{} trials:{}", id, set.size(), adapter.trials);
            allCandidates.addAll(adapter.candidates);
        }

        purgeCandidates(allCandidates);
        Collections.sort(allCandidates, Candidate.byReverseGrade);

        return allCandidates;
    }

    //--------------------//
    // retrieveComponents //
    //--------------------//
    /**
     * Look into key signature area for key items, based on connected components.
     *
     * @param keyShape expected alter shape
     */
    public void retrieveComponents (Shape keyShape)
    {
        logger.debug("Key for staff#{}", id);

        List<Candidate> allCandidates = retrieveCandidates(Collections.singleton(keyShape));

        for (Candidate candidate : allCandidates) {
            final KeySlice slice = roi.sliceOf(candidate.glyph.getCentroid().x);

            if ((slice.eval == null) || (slice.eval.grade < candidate.eval.grade)) {
                slice.eval = candidate.eval;
                slice.glyph = candidate.glyph;
            }
        }

        for (KeySlice slice : roi) {
            if (slice.eval != null) {
                double grade = Inter.intrinsicRatio * slice.eval.grade;
                KeyAlterInter alterInter = KeyAlterInter.create(
                        slice.glyph,
                        slice.eval.shape,
                        grade,
                        staff);
                sig.addVertex(alterInter);
                slice.alter = alterInter;
            }

            logger.debug("{}", slice);
        }
    }

    //-------------//
    // sliceHasInk //
    //-------------//
    /**
     * Report whether the provided slice rectangle contains enough ink for an alter.
     *
     * @param rect provided slice rectangle
     * @return true if enough ink
     */
    public boolean sliceHasInk (Rectangle rect)
    {
        final int ink = getInk(rect);

        return ink >= params.minGlyphWeight;
    }

    //--------//
    // getInk //
    //--------//
    /**
     * Report the amount of ink in the provided rectangle of the staff-free buffer.
     *
     * @param rect provided rectangle
     * @return number of foreground pixels, off staff lines
     */
    private int getInk (Rectangle rect)
    {
        final int xMin = rect.x;
        final int xMax = (rect.x + rect.width) - 1;
        final int yMin = rect.y;
        final int yMax = (rect.y + rect.height) - 1;
        int weight = 0;

        for (int x = xMin; x <= xMax; x++) {
            for (int y = yMin; y <= yMax; y++) {
                if (staffFreeSource.get(x, y) == 0) {
                    weight++;
                }
            }
        }

        return weight;
    }

    //-----------------//
    // purgeCandidates //
    //-----------------//
    /**
     * Make sure that no part is shared by different candidates.
     */
    private void purgeCandidates (List<Candidate> candidates)
    {
        final List<Candidate> toRemove = new ArrayList<Candidate>();
        Collections.sort(candidates, Candidate.byReverseGrade);

        for (int i = 0; i < candidates.size(); i++) {
            final Candidate candidate = candidates.get(i);

            for (Glyph part : candidate.parts) {
                toRemove.clear();

                for (Candidate c : candidates.subList(i + 1, candidates.size())) {
                    if (c.parts.contains(part)) {
                        toRemove.add(c);
                    }
                }

                candidates.removeAll(toRemove);
            }
        }
    }

    //------------//
    // purgeParts //
    //------------//
    /**
     * Purge the population of candidate parts as much as possible, since the cost
     * of their later combinations is exponential.
     * <p>
     * Those of width 1 and stuck on right side of slice can be safely removed, since they
     * certainly belong to the stem of the next slice.
     * <p>
     * Those composed of just one (isolated) pixel are also removed, although this is more
     * questionable.
     *
     * @param parts the collection to purge
     * @param xMax  maximum abscissa in area
     */
    private void purgeParts (List<Glyph> parts,
                             int xMax)
    {
        final List<Glyph> toRemove = new ArrayList<Glyph>();

        for (Glyph glyph : parts) {
            if ((glyph.getWeight() < params.minPartWeight) || (glyph.getBounds().x == xMax)) {
                toRemove.add(glyph);
            }
        }

        if (!toRemove.isEmpty()) {
            parts.removeAll(toRemove);
        }

        if (parts.size() > params.maxPartCount) {
            Collections.sort(parts, Glyphs.byReverseWeight);
            parts.retainAll(parts.subList(0, params.maxPartCount));
        }
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //-----------//
    // Candidate //
    //-----------//
    /**
     * Meant to mutually exclude candidates that have a part in common.
     */
    public static class Candidate
    {
        //~ Static fields/initializers -------------------------------------------------------------

        /** To sort according to decreasing grade. */
        public static final Comparator<Candidate> byReverseGrade = new Comparator<Candidate>()
        {
            @Override
            public int compare (Candidate c1,
                                Candidate c2)
            {
                if (c1 == c2) {
                    return 0;
                }

                return Double.compare(c2.eval.grade, c1.eval.grade);
            }
        };

        /** To sort according to left abscissa. */
        public static final Comparator<Candidate> byAbscissa = new Comparator<Candidate>()
        {
            @Override
            public int compare (Candidate c1,
                                Candidate c2)
            {
                if (c1 == c2) {
                    return 0;
                }

                return Double.compare(c1.glyph.getLeft(), c2.glyph.getLeft());
            }
        };

        //~ Instance fields ------------------------------------------------------------------------
        final Glyph glyph;

        final Set<Glyph> parts;

        final Evaluation eval;

        //~ Constructors ---------------------------------------------------------------------------
        public Candidate (Glyph glyph,
                          Set<Glyph> parts,
                          Evaluation eval)
        {
            this.glyph = glyph;
            this.parts = parts;
            this.eval = eval;
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public String toString ()
        {
            StringBuilder sb = new StringBuilder("Candidate{#");

            sb.append(glyph.getId());
            sb.append(" ").append(eval);
            sb.append("}");

            return sb.toString();
        }
    }

    //--------------------//
    // AbstractKeyAdapter //
    //--------------------//
    /**
     * Abstract adapter for retrieving items.
     */
    private abstract class AbstractKeyAdapter
            extends GlyphCluster.AbstractAdapter
    {
        //~ Instance fields ------------------------------------------------------------------------

        /** Minimum acceptable intrinsic grade. */
        protected final double minGrade;

        /** Relevant shapes. */
        protected final EnumSet<Shape> targetShapes = EnumSet.noneOf(Shape.class);

        //~ Constructors ---------------------------------------------------------------------------
        public AbstractKeyAdapter (SimpleGraph<Glyph, GlyphLink> graph,
                                   Set<Shape> targetShapes,
                                   double minGrade)
        {
            super(graph);
            this.targetShapes.addAll(targetShapes);
            this.minGrade = minGrade;
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public boolean isTooHeavy (int weight)
        {
            return weight > params.maxGlyphWeight;
        }

        @Override
        public boolean isTooLarge (Rectangle bounds)
        {
            return (bounds.width > params.maxGlyphWidth)
                   || (bounds.height > params.maxGlyphHeight);
        }

        @Override
        public boolean isTooLight (int weight)
        {
            return weight < params.minGlyphWeight;
        }

        @Override
        public boolean isTooSmall (Rectangle bounds)
        {
            return (bounds.width < params.minGlyphWidth)
                   || (bounds.height < params.minGlyphHeight);
        }

        protected boolean embracesSlicePeaks (KeySlice slice,
                                              Glyph glyph)
        {
            final Rectangle sliceBox = slice.getRect();
            final int sliceStart = sliceBox.x;
            final int sliceStop = (sliceBox.x + sliceBox.width) - 1;
            final Rectangle glyphBox = glyph.getBounds();

            // Make sure that the glyph width embraces the slice peak(s)
            for (KeyPeak peak : peaks) {
                final double peakCenter = peak.getCenter();

                if ((sliceStart <= peakCenter) && (peakCenter <= sliceStop)) {
                    // Is this slice peak embraced by glyph?
                    if (!GeoUtil.xEmbraces(glyphBox, peakCenter)) {
                        return false;
                    }
                }
            }

            return true;
        }

        protected void evaluateSliceGlyph (KeySlice slice,
                                           Glyph glyph,
                                           Set<Glyph> parts)
        {
            if (isTooSmall(glyph.getBounds())) {
                return;
            }

            if ((slice != null) && !embracesSlicePeaks(slice, glyph)) {
                return;
            }

            trials++;

            if (glyph.getId() == 0) {
                glyph = sheet.getGlyphIndex().registerOriginal(glyph);
                system.addFreeGlyph(glyph);
            }

            if (glyph.isVip()) {
                logger.info("VIP evaluateSliceGlyph for {}", glyph);
            }

            glyphCandidates.add(glyph);

            Evaluation[] evals = classifier.getNaturalEvaluations(glyph, sheet.getInterline());

            for (Shape shape : targetShapes) {
                Evaluation eval = evals[shape.ordinal()];
                double grade = Inter.intrinsicRatio * eval.grade;

                if (grade >= minGrade) {
                    logger.debug("glyph#{} width:{} {}", glyph.getId(), glyph.getWidth(), eval);
                    keepCandidate(glyph, parts, eval);
                }
            }
        }

        protected abstract void keepCandidate (Glyph glyph,
                                               Set<Glyph> parts,
                                               Evaluation eval);
    }

    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
            extends ConstantSet
    {
        //~ Instance fields ------------------------------------------------------------------------

        private final Constant.Integer maxPartCount = new Constant.Integer(
                "Glyphs",
                8,
                "Maximum number of parts considered for an alter symbol");

        private final Scale.AreaFraction minPartWeight = new Scale.AreaFraction(
                0.01,
                "Minimum weight for an alter part");

        private final Scale.Fraction maxPartGap = new Scale.Fraction(
                1.5,
                "Maximum distance between two parts of a single alter symbol");

        private final Scale.Fraction minGlyphWidth = new Scale.Fraction(
                0.5,
                "Minimum glyph width");

        private final Scale.Fraction maxGlyphWidth = new Scale.Fraction(
                2.0,
                "Maximum glyph width");

        private final Scale.Fraction minGlyphHeight = new Scale.Fraction(
                1.0,
                "Minimum glyph height");

        private final Scale.Fraction maxGlyphHeight = new Scale.Fraction(
                3.5,
                "Maximum glyph height");

        private final Scale.AreaFraction minGlyphWeight = new Scale.AreaFraction(
                0.2,
                "Minimum glyph weight");

        private final Scale.AreaFraction maxGlyphWeight = new Scale.AreaFraction(
                2.9,
                "Maximum glyph weight");
    }

    //-----------------//
    // MultipleAdapter //
    //-----------------//
    /**
     * Adapter for retrieving all items of the key (in key area).
     */
    private class MultipleAdapter
            extends AbstractKeyAdapter
    {
        //~ Instance fields ------------------------------------------------------------------------

        final List<Candidate> candidates = new ArrayList<Candidate>();

        //~ Constructors ---------------------------------------------------------------------------
        public MultipleAdapter (SimpleGraph<Glyph, GlyphLink> graph,
                                Set<Shape> targetShapes,
                                double minGrade)
        {
            super(graph, targetShapes, minGrade);
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void evaluateGlyph (Glyph glyph,
                                   Set<Glyph> parts)
        {
            // Retrieve impacted slice
            final KeySlice slice = roi.sliceOf(glyph.getCentroid().x);
            evaluateSliceGlyph(slice, glyph, parts);
        }

        @Override
        protected void keepCandidate (Glyph glyph,
                                      Set<Glyph> parts,
                                      Evaluation eval)
        {
            candidates.add(new Candidate(glyph, parts, eval));
        }
    }

    //------------//
    // Parameters //
    //------------//
    private static class Parameters
    {
        //~ Instance fields ------------------------------------------------------------------------

        final int maxPartCount;

        final int minPartWeight;

        final double maxPartGap;

        final double minGlyphWidth;

        final double maxGlyphWidth;

        final double minGlyphHeight;

        final double maxGlyphHeight;

        final int minGlyphWeight;

        final int maxGlyphWeight;

        //~ Constructors ---------------------------------------------------------------------------
        public Parameters (Scale scale)
        {
            maxPartCount = constants.maxPartCount.getValue();
            minPartWeight = scale.toPixels(constants.minPartWeight);
            maxPartGap = scale.toPixelsDouble(constants.maxPartGap);
            minGlyphWidth = scale.toPixelsDouble(constants.minGlyphWidth);
            maxGlyphWidth = scale.toPixelsDouble(constants.maxGlyphWidth);
            minGlyphHeight = scale.toPixelsDouble(constants.minGlyphHeight);
            maxGlyphHeight = scale.toPixelsDouble(constants.maxGlyphHeight);
            minGlyphWeight = scale.toPixels(constants.minGlyphWeight);
            maxGlyphWeight = scale.toPixels(constants.maxGlyphWeight);
        }
    }

    //---------------//
    // SingleAdapter //
    //---------------//
    /**
     * Adapter for retrieving one key item (in a slice).
     */
    private class SingleAdapter
            extends AbstractKeyAdapter
    {
        //~ Instance fields ------------------------------------------------------------------------

        /** Related slice. */
        private final KeySlice slice;

        //~ Constructors ---------------------------------------------------------------------------
        public SingleAdapter (KeySlice slice,
                              List<Glyph> parts,
                              Set<Shape> targetShapes,
                              double minGrade)
        {
            super(Glyphs.buildLinks(parts, params.maxPartGap), targetShapes, minGrade);
            this.slice = slice;
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void evaluateGlyph (Glyph glyph,
                                   Set<Glyph> parts)
        {
            evaluateSliceGlyph(slice, glyph, parts);
        }

        @Override
        protected void keepCandidate (Glyph glyph,
                                      Set<Glyph> parts,
                                      Evaluation eval)
        {
            if ((slice.eval == null) || (slice.eval.grade < eval.grade)) {
                slice.eval = eval;
                slice.glyph = glyph;
            }
        }
    }
}
