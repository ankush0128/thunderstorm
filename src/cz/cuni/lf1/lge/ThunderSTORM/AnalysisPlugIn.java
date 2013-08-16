package cz.cuni.lf1.lge.ThunderSTORM;

import static cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.PSFModel.Params.LABEL_X;
import static cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.PSFModel.Params.LABEL_Y;
import static cz.cuni.lf1.lge.ThunderSTORM.util.ImageProcessor.subtract;
import cz.cuni.lf1.lge.ThunderSTORM.UI.AnalysisOptionsDialog;
import cz.cuni.lf1.lge.ThunderSTORM.UI.GUI;
import cz.cuni.lf1.lge.ThunderSTORM.UI.MacroParser;
import cz.cuni.lf1.lge.ThunderSTORM.UI.RenderingOverlay;
import cz.cuni.lf1.lge.ThunderSTORM.detectors.IDetector;
import cz.cuni.lf1.lge.ThunderSTORM.detectors.ui.IDetectorUI;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.Molecule;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.MoleculeDescriptor;
import static cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.MoleculeDescriptor.Units.PIXEL_SQUARED;
import static cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.MoleculeDescriptor.Units.NANOMETER_SQUARED;
import static cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.MoleculeDescriptor.Units.MICROMETER_SQUARED;
import static cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.MoleculeDescriptor.Units.DIGITAL;
import static cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.MoleculeDescriptor.Units.MICROMETER;
import static cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.MoleculeDescriptor.Units.NANOMETER;
import static cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.MoleculeDescriptor.Units.PHOTON;
import static cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.MoleculeDescriptor.Units.PIXEL;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.PSFModel;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.ui.IEstimatorUI;
import cz.cuni.lf1.lge.ThunderSTORM.filters.ui.IFilterUI;
import cz.cuni.lf1.lge.ThunderSTORM.rendering.IncrementalRenderingMethod;
import cz.cuni.lf1.lge.ThunderSTORM.rendering.RenderingQueue;
import cz.cuni.lf1.lge.ThunderSTORM.rendering.ui.IRendererUI;
import cz.cuni.lf1.lge.ThunderSTORM.results.IJResultsTable;
import cz.cuni.lf1.lge.ThunderSTORM.thresholding.Thresholder;
import cz.cuni.lf1.lge.ThunderSTORM.util.Math;
import cz.cuni.lf1.lge.ThunderSTORM.util.Point;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.filter.ExtendedPlugInFilter;
import static ij.plugin.filter.PlugInFilter.DOES_16;
import static ij.plugin.filter.PlugInFilter.DOES_32;
import static ij.plugin.filter.PlugInFilter.DOES_8G;
import static ij.plugin.filter.PlugInFilter.DOES_STACKS;
import static ij.plugin.filter.PlugInFilter.DONE;
import static ij.plugin.filter.PlugInFilter.FINAL_PROCESSING;
import static ij.plugin.filter.PlugInFilter.NO_CHANGES;
import static ij.plugin.filter.PlugInFilter.NO_UNDO;
import static ij.plugin.filter.PlugInFilter.PARALLELIZE_STACKS;
import static ij.plugin.filter.PlugInFilter.SUPPORTS_MASKING;
import ij.plugin.filter.PlugInFilterRunner;
import ij.plugin.frame.Recorder;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import java.awt.Color;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ThunderSTORM Analysis plugin.
 *
 * Open the options dialog, process an image stack to recieve a list of
 * localized molecules which will get displayed in the {@code ResultsTable} and
 * previed in a new {@code ImageStack} with detections marked as crosses in
 * {@code Overlay} of each slice of the stack.
 */
public final class AnalysisPlugIn implements ExtendedPlugInFilter {

    private int stackSize;
    private AtomicInteger nProcessed = new AtomicInteger(0);
    private final int pluginFlags = DOES_8G | DOES_16 | DOES_32 | NO_CHANGES
            | NO_UNDO | DOES_STACKS | PARALLELIZE_STACKS | FINAL_PROCESSING | SUPPORTS_MASKING;
    private List<Molecule>[] results;
    private List<IFilterUI> allFilters;
    private List<IDetectorUI> allDetectors;
    private List<IEstimatorUI> allEstimators;
    private List<IRendererUI> allRenderers;
    private int selectedFilter;
    private int selectedEstimator;
    private int selectedDetector;
    private int selectedRenderer;
    private RenderingQueue renderingQueue;
    private ImagePlus renderedImage;
    private Roi roi;

    /**
     * Returns flags specifying capabilities of the plugin.
     *
     * This method is called before an actual analysis and returns flags
     * supported by the plugin. The method is also called after the processing
     * is finished to fill the {@code ResultsTable} and to visualize the
     * detections directly in image stack (a new copy of image stack is
     * created).
     *
     * <strong>The {@code ResultsTable} is always guaranteed to contain columns
     * <i>frame, x, y</i>!</strong> The other parameters are optional and can
     * change for different PSFs.
     *
     * @param command command
     * @param imp ImagePlus instance holding the active image (not required)
     * @return flags specifying capabilities of the plugin
     */
    @Override
    public int setup(String command, ImagePlus imp) {
        GUI.setLookAndFeel();
        CameraSetupPlugIn.loadPreferences();
        //
        if(command.equals("final")) {
            IJ.showStatus("ThunderSTORM is generating the results...");
            //
            // Show table with results
            IJResultsTable rt = IJResultsTable.getResultsTable();
            rt.reset();
            rt.setOriginalState();
            for(int frame = 1; frame <= stackSize; frame++) {
                if(results[frame] != null) {
                    for(Molecule psf : results[frame]) {
                        psf.insertParamAt(0, MoleculeDescriptor.LABEL_FRAME, MoleculeDescriptor.Units.UNITLESS, (double) frame);
                        rt.addRow(psf);
                    }
                }
            }
            convertAllColumnsToAnalogUnits(rt);
            calculateThompsonFormula(rt);
            rt.insertIdColumn();
            rt.copyOriginalToActual();
            rt.setActualState();
            rt.setPreviewRenderer(renderingQueue);
            setDefaultColumnsWidth(rt);
            rt.show();
            //
            // Show detections in the image
            imp.setOverlay(null);
            RenderingOverlay.showPointsInImage(rt, imp, roi, Color.red, RenderingOverlay.MARKER_CROSS);
            renderingQueue.repaintLater();
            //
            // Finished
            IJ.showProgress(1.0);
            IJ.showStatus("ThunderSTORM finished.");
            return DONE;
        } else if("showResultsTable".equals(command)) {
            IJResultsTable.getResultsTable().show();
            return DONE;
        } else {
            return pluginFlags; // Grayscale only, no changes to the image and therefore no undo
        }
    }

    /**
     * Show the options dialog for a particular command and block the current
     * processing thread until user confirms his settings or cancels the
     * operation.
     *
     * @param command command (not required)
     * @param imp ImagePlus instance holding the active image (not required)
     * @param pfr instance that initiated this plugin (not required)
     * @return
     */
    @Override
    public int showDialog(ImagePlus imp, String command, PlugInFilterRunner pfr) {
        try {
            // load modules
            allFilters = ThreadLocalWrapper.wrapFilters(ModuleLoader.getUIModules(IFilterUI.class));
            allDetectors = ThreadLocalWrapper.wrapDetectors(ModuleLoader.getUIModules(IDetectorUI.class));
            allEstimators = ThreadLocalWrapper.wrapEstimators(ModuleLoader.getUIModules(IEstimatorUI.class));
            allRenderers = ModuleLoader.getUIModules(IRendererUI.class);

            if(MacroParser.isRanFromMacro()) {
                //parse the macro options
                MacroParser parser = new MacroParser(allFilters, allEstimators, allDetectors, allRenderers);
                selectedFilter = parser.getFilterIndex();
                selectedDetector = parser.getDetectorIndex();
                selectedEstimator = parser.getEstimatorIndex();
                
                roi = imp.getRoi() != null ? imp.getRoi() : new Roi(0, 0, imp.getWidth(), imp.getHeight());
                IRendererUI rendererPanel = parser.getRendererUI();
                rendererPanel.setSize(roi.getBounds().width, roi.getBounds().height);
                IncrementalRenderingMethod method = rendererPanel.getImplementation();
                renderedImage = (method != null) ? method.getRenderedImage() : null;
                renderingQueue = new RenderingQueue(method, new RenderingQueue.DefaultRepaintTask(renderedImage), rendererPanel.getRepaintFrequency());
                return pluginFlags;
            } else {
                // Create and show the dialog
                AnalysisOptionsDialog dialog = new AnalysisOptionsDialog(imp, command, allFilters, allDetectors, allEstimators, allRenderers);
                dialog.setVisible(true);
                if(dialog.wasCanceled()) {  // This is a blocking call!!
                    return DONE;    // cancel
                }
                selectedFilter = dialog.getFilterIndex();
                selectedDetector = dialog.getDetectorIndex();
                selectedEstimator = dialog.getEstimatorIndex();
                selectedRenderer = dialog.getRendererIndex();
                
                roi = imp.getRoi() != null ? imp.getRoi() : new Roi(0, 0, imp.getWidth(), imp.getHeight());
                IRendererUI renderer = allRenderers.get(selectedRenderer);
                renderer.setSize(roi.getBounds().width, roi.getBounds().height);
                IncrementalRenderingMethod method = renderer.getImplementation();
                renderedImage = (method != null) ? method.getRenderedImage() : null;
                renderingQueue = new RenderingQueue(method, new RenderingQueue.DefaultRepaintTask(renderedImage), renderer.getRepaintFrequency());

                //if recording window is open, record parameters of all modules
                if(Recorder.record) {
                    MacroParser.recordFilterUI(dialog.getFilter());
                    MacroParser.recordDetectorUI(dialog.getDetector());
                    MacroParser.recordEstimatorUI(dialog.getEstimator());
                    MacroParser.recordRendererUI(dialog.getRenderer());
                }
            }
        } catch(Exception ex) {
            IJ.handleException(ex);
            return DONE;
        }
        //
        try {
            Thresholder.loadFilters(allFilters);
            Thresholder.setActiveFilter(selectedFilter);   // !! must be called before any threshold is evaluated !!
            Thresholder.parseThreshold(allDetectors.get(selectedFilter).getImplementation().getThresholdFormula());
        } catch(Exception ex) {
            IJ.error("Error parsing threshold formula! " + ex.toString());
        }
        //
        return pluginFlags; // ok
    }

    /**
     * Gives the plugin information about the number of passes through the image
     * stack we want to process.
     *
     * Allocation of resources to store the results is done here.
     *
     * @param nPasses number of passes through the image stack we want to
     * process
     */
    @Override
    public void setNPasses(int nPasses) {
        stackSize = nPasses;
        nProcessed.set(0);
        results = new Vector[stackSize + 1];  // indexing from 1 for simplicity
    }

    /**
     * Run the plugin.
     *
     * This method is ran in parallel, thus counting the results must be done
     * atomicaly.
     *
     * @param ip input image
     */
    @Override
    public void run(ImageProcessor ip) {
        assert (selectedFilter >= 0 && selectedFilter < allFilters.size()) : "Index out of bounds: selectedFilter!";
        assert (selectedDetector >= 0 && selectedDetector < allDetectors.size()) : "Index out of bounds: selectedDetector!";
        assert (selectedEstimator >= 0 && selectedEstimator < allEstimators.size()) : "Index out of bounds: selectedEstimator!";
        assert (selectedRenderer >= 0 && selectedRenderer < allRenderers.size()) : "Index out of bounds: selectedRenderer!";
        assert (renderingQueue != null) : "Renderer was not selected!";
        //
        ip.setRoi(roi);
        FloatProcessor fp = subtract((FloatProcessor) ip.crop().convertToFloat(), (float) CameraSetupPlugIn.offset);
        if(roi != null) {
            fp.setMask(roi.getMask());
        }
        Vector<Molecule> fits;
        try {
            Thresholder.setCurrentImage(fp);
            FloatProcessor filtered = allFilters.get(selectedFilter).getImplementation().filterImage(fp);
            IDetector detector = allDetectors.get(selectedDetector).getImplementation();
            Vector<Point> detections = detector.detectMoleculeCandidates(filtered);
            fits = allEstimators.get(selectedEstimator).getImplementation().estimateParameters(fp, Point.applyRoiMask(roi, detections));
            results[ip.getSliceNumber()] = fits;
            nProcessed.incrementAndGet();

            if(fits.size() > 0) {
                renderingQueue.renderLater(fits);
            }
            //
            IJ.showProgress((double) nProcessed.intValue() / (double) stackSize);
            IJ.showStatus("ThunderSTORM processing frame " + nProcessed + " of " + stackSize + "...");
        } catch(Exception ex) {
            IJ.handleException(ex);
        }
    }

    public static void convertAllColumnsToAnalogUnits(IJResultsTable rt) {
        for(String colName : rt.getColumnNames()) {
            switch(rt.getColumnUnits(colName)) {
                case PIXEL:
                case MICROMETER: // this is of course analog unit, but we need all units to be the same
                    rt.setColumnUnits(colName, NANOMETER);
                    break;
                case PIXEL_SQUARED:
                case MICROMETER_SQUARED: // this is of course analog unit, but we need all units to be the same
                    rt.setColumnUnits(colName, NANOMETER_SQUARED);
                    break;
                case DIGITAL:
                    rt.setColumnUnits(colName, PHOTON);
                    break;
            }
        }
    }
    
    public static void convertAllColumnsToDigitalUnits(IJResultsTable rt) {
        for(String colName : rt.getColumnNames()) {
            switch(rt.getColumnUnits(colName)) {
                case NANOMETER:
                case MICROMETER:
                    rt.setColumnUnits(colName, PIXEL);
                    break;
                case NANOMETER_SQUARED:
                case MICROMETER_SQUARED:
                    rt.setColumnUnits(colName, PIXEL_SQUARED);
                    break;
                case PHOTON:
                    rt.setColumnUnits(colName, DIGITAL);
                    break;
            }
        }
    }

    public static void calculateThompsonFormula(IJResultsTable rt) {
        // Note: even though that the uncertainity can be calculated in pixels,
        //       we choose to do it in nanometers by default setting
        try {
            String paramName;
            double paramValue;
            Molecule mol;
            if(CameraSetupPlugIn.isEmCcd) {
                if(rt.columnExists(MoleculeDescriptor.Fitting.LABEL_CCD_THOMPSON)) {
                    rt.deleteColumn(MoleculeDescriptor.Fitting.LABEL_CCD_THOMPSON);
                }
                //
                paramName = MoleculeDescriptor.Fitting.LABEL_EMCCD_THOMPSON;
                for(int row = 0, max = rt.getRowCount(); row < max; row++) {
                    mol = rt.getRow(row);
                    paramValue = MoleculeDescriptor.Fitting.emccdThompson(mol);
                    if(mol.hasParam(paramName)) {
                        mol.setParam(paramName, paramValue);
                    } else {
                        mol.addParam(paramName, MoleculeDescriptor.Units.getDefaultUnit(paramName), paramValue);
                    }
                }
            } else {
                if(rt.columnExists(MoleculeDescriptor.Fitting.LABEL_EMCCD_THOMPSON)) {
                    rt.deleteColumn(MoleculeDescriptor.Fitting.LABEL_EMCCD_THOMPSON);
                }
                //
                paramName = MoleculeDescriptor.Fitting.LABEL_CCD_THOMPSON;
                for(int row = 0, max = rt.getRowCount(); row < max; row++) {
                    mol = rt.getRow(row);
                    paramValue = MoleculeDescriptor.Fitting.ccdThompson(mol);
                    if(mol.hasParam(paramName)) {
                        mol.setParam(paramName, paramValue);
                    } else {
                        mol.addParam(paramName, MoleculeDescriptor.Units.getDefaultUnit(paramName), paramValue);
                    }
                }
            }
            rt.fireStructureChanged();
        } catch(Exception e) {
            // ignore...PSF does not fit all the required parameters
        }
    }


    public static void setDefaultColumnsWidth(IJResultsTable rt) {
        rt.setColumnPreferredWidth(MoleculeDescriptor.LABEL_ID, 40);
        rt.setColumnPreferredWidth(MoleculeDescriptor.LABEL_FRAME, 40);
        rt.setColumnPreferredWidth(PSFModel.Params.LABEL_X, 60);
        rt.setColumnPreferredWidth(PSFModel.Params.LABEL_Y, 60);
        rt.setColumnPreferredWidth(PSFModel.Params.LABEL_SIGMA, 40);
        rt.setColumnPreferredWidth(PSFModel.Params.LABEL_SIGMA1, 40);
        rt.setColumnPreferredWidth(PSFModel.Params.LABEL_SIGMA1, 40);
    }
}
