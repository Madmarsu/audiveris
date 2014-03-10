//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                       W e d g e I n t e r                                      //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//  Copyright © Herve Bitteur and others 2000-2014. All rights reserved.
//  This software is released under the GNU General Public License.
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package omr.sig;

import omr.glyph.Shape;

import java.awt.Rectangle;
import java.awt.geom.Line2D;

/**
 * Class {@code WedgeInter} represents a wedge (crescendo or diminuendo).
 *
 * @author Hervé Bitteur
 */
public class WedgeInter
        extends AbstractInter
{
    //~ Instance fields ----------------------------------------------------------------------------

    private final SegmentInter s1;

    private final SegmentInter s2;

    private final Line2D l1;

    private final Line2D l2;

    //~ Constructors -------------------------------------------------------------------------------
    /**
     * Creates a new WedgeInter object.
     *
     * @param s1      first segment
     * @param s2      second segment
     * @param l1      precise first line
     * @param l2      precise second line
     * @param bounds  bounding box
     * @param shape   CRESCENDO or DECRESCENDO
     * @param impacts assignments details
     */
    public WedgeInter (SegmentInter s1,
                       SegmentInter s2,
                       Line2D l1,
                       Line2D l2,
                       Rectangle bounds,
                       Shape shape,
                       GradeImpacts impacts)
    {
        super(bounds, shape, impacts.getGrade());
        setImpacts(impacts);
        this.s1 = s1;
        this.s2 = s2;
        this.l1 = l1;
        this.l2 = l2;
    }

    //~ Methods ------------------------------------------------------------------------------------
    //--------//
    // accept //
    //--------//
    @Override
    public void accept (InterVisitor visitor)
    {
        visitor.visit(this);
    }

    //----------//
    // getLine1 //
    //----------//
    public Line2D getLine1 ()
    {
        return l1;
    }

    //----------//
    // getLine2 //
    //----------//
    public Line2D getLine2 ()
    {
        return l2;
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //---------//
    // Impacts //
    //---------//
    public static class Impacts
            extends BasicImpacts
    {
        //~ Static fields/initializers -------------------------------------------------------------

        private static final String[] NAMES = new String[]{
            "s1", "s2", "closedDy", "openDy", "openBias"
        };

        private static final double[] WEIGHTS = new double[]{1, 1, 1, 1, 1};

        //~ Constructors ---------------------------------------------------------------------------
        public Impacts (double s1,
                        double s2,
                        double closedDy,
                        double openDy,
                        double openBias)
        {
            super(NAMES, WEIGHTS);
            setImpact(0, s1);
            setImpact(1, s2);
            setImpact(2, closedDy);
            setImpact(3, openDy);
            setImpact(4, openBias);
        }
    }
}