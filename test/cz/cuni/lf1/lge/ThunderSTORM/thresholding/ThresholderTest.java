package cz.cuni.lf1.lge.ThunderSTORM.thresholding;

import cz.cuni.lf1.lge.ThunderSTORM.filters.EmptyFilter;
import cz.cuni.lf1.lge.ThunderSTORM.filters.ui.IFilterUI;
import java.util.Vector;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author Josef Borkovec <josef.borkovec[at]lf1.cuni.cz>
 */
public class ThresholderTest {

  @Test
  public void testSimpleNumber() {
    Vector<IFilterUI> filters = new Vector<IFilterUI>();
    filters.add(new EmptyFilter());
    Thresholder.loadFilters(filters);
    Thresholder.setActiveFilter(0);
    double thr = Thresholder.getThreshold("120.5");
    assertEquals(120.5, thr, 0.001);
  }
  
  
}