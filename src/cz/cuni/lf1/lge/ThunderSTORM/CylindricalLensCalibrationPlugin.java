package cz.cuni.lf1.lge.ThunderSTORM;

import cz.cuni.lf1.lge.ThunderSTORM.UI.CalibrationDialog;
import cz.cuni.lf1.lge.ThunderSTORM.UI.MacroParser;
import cz.cuni.lf1.lge.ThunderSTORM.UI.RenderingOverlay;
import cz.cuni.lf1.lge.ThunderSTORM.calibration.PSFSeparator.Position;
import cz.cuni.lf1.lge.ThunderSTORM.calibration.PolynomialCalibration;
import cz.cuni.lf1.lge.ThunderSTORM.detectors.ui.IDetectorUI;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.ui.CalibrationEstimatorUI;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.ui.IEstimatorUI;
import cz.cuni.lf1.lge.ThunderSTORM.filters.ui.IFilterUI;
import cz.cuni.lf1.lge.ThunderSTORM.thresholding.Thresholder;
import cz.cuni.lf1.lge.ThunderSTORM.UI.GUI;
import cz.cuni.lf1.lge.ThunderSTORM.calibration.CalibrationProcess;
import cz.cuni.lf1.lge.ThunderSTORM.calibration.QuadraticFunction;
import cz.cuni.lf1.lge.ThunderSTORM.util.Math;
import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.gui.Plot;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import ij.process.FloatProcessor;
import java.awt.Color;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import org.yaml.snakeyaml.Yaml;
import ij.gui.Roi;
import java.awt.Rectangle;

public class CylindricalLensCalibrationPlugin implements PlugIn {

    double angle;
    IFilterUI selectedFilterUI;
    IDetectorUI selectedDetectorUI;
    CalibrationEstimatorUI calibrationEstimatorUI;
    String savePath;
    double stageStep;
    ImagePlus imp;
    Roi roi;

    @Override
    public void run(String arg) {
        GUI.setLookAndFeel();
        //
        imp = IJ.getImage();
        if(imp == null) {
            IJ.error("No image open.");
            return;
        }
        if(imp.getImageStackSize() < 2) {
            IJ.error("Requires a stack.");
            return;
        }
        try {
            //load modules
            calibrationEstimatorUI = new CalibrationEstimatorUI();
            List<IFilterUI> filters = ModuleLoader.getUIModules(IFilterUI.class);
            List<IDetectorUI> detectors = ModuleLoader.getUIModules(IDetectorUI.class);
            List<IEstimatorUI> estimators = Arrays.asList(new IEstimatorUI[]{calibrationEstimatorUI}); // only one estimator can be used
            Thresholder.loadFilters(filters);

            // get user options
            if(MacroParser.isRanFromMacro()) {
                //parse macro parameters
                MacroParser parser = new MacroParser(filters, estimators, detectors, null);
                selectedFilterUI = parser.getFilterUI();
                selectedDetectorUI = parser.getDetectorUI();
                parser.getEstimatorUI();
                savePath = Macro.getValue(Macro.getOptions(), "saveto", null);
                stageStep = Double.parseDouble(Macro.getValue(Macro.getOptions(), "stageStep", "10"));
            } else {
                //show dialog
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch(Exception e) {
                    IJ.handleException(e);
                }
                CalibrationDialog dialog;
                dialog = new CalibrationDialog(filters, detectors, estimators);
                dialog.setVisible(true);
                if(dialog.waitForResult() != JOptionPane.OK_OPTION) {
                    return;
                }
                selectedFilterUI = dialog.getActiveFilterUI();
                selectedDetectorUI = dialog.getActiveDetectorUI();
                savePath = dialog.getSavePath();
                stageStep = dialog.getStageStep();

                //if recording window is open, record parameters
                if(Recorder.record) {
                    MacroParser.recordFilterUI(selectedFilterUI);
                    MacroParser.recordDetectorUI(selectedDetectorUI);
                    MacroParser.recordEstimatorUI(calibrationEstimatorUI);
                    Recorder.recordOption("saveto", savePath.replace("\\", "\\\\"));
                    Recorder.recordOption("stageStep", stageStep + "");
                }
            }
            roi = imp.getRoi() != null ? imp.getRoi() : new Roi(0, 0, imp.getWidth(), imp.getHeight());

            //perform the calibration
            CalibrationProcess process = new CalibrationProcess(selectedFilterUI, selectedDetectorUI, calibrationEstimatorUI, stageStep, imp, roi);

            process.estimateAngle();
            IJ.log("angle = " + process.getAngle());

            process.fitQuadraticPolynomials();
            IJ.log("s1 = " + process.getPolynomS1Final().toString());
            IJ.log("s2 = " + process.getPolynomS2Final().toString());

            drawOverlay(imp, process.getBeadPositions(), process.getUsedPositions());
            drawSigmaPlots(process.getAllPolynomsS1(), process.getAllPolynomsS2(),
                    process.getPolynomS1Final().convertToFrames(stageStep), process.getPolynomS2Final().convertToFrames(stageStep),
                    process.getAllFrames(), process.getAllSigma1s(), process.getAllSigma2s());

            saveToFile(savePath, process.getCalibration());
        } catch(IOException ex) {
            IJ.error("Could not write calibration file: " + ex.getMessage());
        } catch(Exception ex) {
            IJ.handleException(ex);
        }
    }

    private void saveToFile(String path, PolynomialCalibration calibration) throws IOException {
        FileWriter fw = null;
        try {
            Yaml yaml = new Yaml();
            fw = new FileWriter(path);
            yaml.dump(calibration, fw);
            IJ.showStatus("Calibration file saved to " + path);
        } catch(IOException e) {
            throw e;
        } finally {
            if(fw != null) {
                fw.close();
            }
        }

    }

    private void drawSigmaPlots(List<QuadraticFunction> sigma1Quadratics, List<QuadraticFunction> sigma2Quadratics,
            QuadraticFunction sigma1param, QuadraticFunction sigma2param,
            double[] allFrames, double[] allSigma1s, double[] allSigma2s) {

        Plot plot = new Plot("Sigma", "z[slices]", "sigma", (float[]) null, (float[]) null);
        plot.setSize(1024, 768);
        //range
        int range = imp.getStackSize() / 2;
        plot.setLimits(-range, +range, 0, 10);
        double[] xVals = new double[range * 2 + 1];
        for(int val = -range, i = 0; val <= range; val++, i++) {
            xVals[i] = val;
        }
        plot.draw();
        //add points
        plot.setColor(new Color(255, 200, 200));
        plot.addPoints(allFrames, allSigma1s, Plot.CROSS);
        plot.setColor(new Color(200, 200, 255));
        plot.addPoints(allFrames, allSigma2s, Plot.CROSS);

        //add polynomials
        for(int i = 0; i < sigma1Quadratics.size(); i++) {
            double[] sigma1Vals = new double[xVals.length];
            double[] sigma2Vals = new double[xVals.length];
            for(int j = 0; j < sigma1Vals.length; j++) {
                sigma1Vals[j] = sigma1Quadratics.get(i).value(xVals[j]);
                sigma2Vals[j] = sigma2Quadratics.get(i).value(xVals[j]);
            }
            plot.setColor(new Color(255, 230, 230));
            plot.addPoints(xVals, sigma1Vals, Plot.LINE);
            plot.setColor(new Color(230, 230, 255));
            plot.addPoints(xVals, sigma2Vals, Plot.LINE);
        }

        //add final fitted curves
        double[] sigma1ValsAll = new double[xVals.length];
        double[] sigma2ValsAll = new double[xVals.length];
        for(int j = 0; j < sigma1ValsAll.length; j++) {
            sigma1ValsAll[j] = sigma1param.value(xVals[j]);
            sigma2ValsAll[j] = sigma2param.value(xVals[j]);
        }
        plot.setColor(new Color(255, 0, 0));
        plot.addPoints(xVals, sigma1ValsAll, Plot.LINE);
        plot.setColor(new Color(0, 0, 255));
        plot.addPoints(xVals, sigma2ValsAll, Plot.LINE);

        //legend
        plot.setColor(Color.red);
        plot.addLabel(0.1, 0.8, "sigma1");
        plot.setColor(Color.blue);
        plot.addLabel(0.1, 0.9, "sigma2");
        plot.show();
    }

    /**
     * draws overlay with each detection and also the positions of beads that
     * were used for fitting polynomials
     *
     */
    private void drawOverlay(ImagePlus imp, List<Position> allPositions, List<Position> usedPositions) {
        imp.setOverlay(null);
        Rectangle roiBounds = roi.getBounds();
        double[] xCentroids = new double[usedPositions.size()];
        double[] yCentroids = new double[usedPositions.size()];
        for(int i = 0; i < xCentroids.length; i++) {
            Position p = usedPositions.get(i);
            xCentroids[i] = p.centroidX + roiBounds.x;
            yCentroids[i] = p.centroidY + roiBounds.y;
        }
        RenderingOverlay.showPointsInImage(imp, xCentroids, yCentroids, Color.red, RenderingOverlay.MARKER_CIRCLE);
        for(Position p : allPositions) {
            double[] frame = p.getFramesAsArray();
            double[] x = Math.add(p.getXAsArray(), roiBounds.x);
            double[] y = Math.add(p.getYAsArray(), roiBounds.y);
            for(int i = 0; i < frame.length; i++) {
                RenderingOverlay.showPointsInImage(imp, new double[]{x[i]}, new double[]{y[i]}, (int) frame[i], Color.BLUE, RenderingOverlay.MARKER_CROSS);
            }
        }

    }

    private void showHistoImages(List<QuadraticFunction> sigma1Quadratics, List<QuadraticFunction> sigma2Quadratics) {
        FloatProcessor a1 = new FloatProcessor(1, sigma1Quadratics.size());
        FloatProcessor a2 = new FloatProcessor(1, sigma2Quadratics.size());
        FloatProcessor b1 = new FloatProcessor(1, sigma2Quadratics.size());
        FloatProcessor b2 = new FloatProcessor(1, sigma2Quadratics.size());
        FloatProcessor cdif = new FloatProcessor(1, sigma2Quadratics.size());

        for(int i = 0; i < sigma1Quadratics.size(); i++) {
            a1.setf(i, (float) sigma1Quadratics.get(i).getA());
            b1.setf(i, (float) sigma1Quadratics.get(i).getB());
            a2.setf(i, (float) sigma2Quadratics.get(i).getA());
            b2.setf(i, (float) sigma2Quadratics.get(i).getB());
            cdif.setf(i, (float) (sigma2Quadratics.get(i).getC() - sigma1Quadratics.get(i).getC()));
        }
        new ImagePlus("a1", a1).show();
        new ImagePlus("a2", a2).show();
        new ImagePlus("b1", b1).show();
        new ImagePlus("b2", b2).show();
        new ImagePlus("cdif", cdif).show();
    }

    private void dumpShiftedPoints(double[] allFrames, double[] allSigma1s, double[] allSigma2s) {
        try {
            FileWriter fw = new FileWriter("d:\\dump.txt");
            fw.append("allFrames:\n");
            fw.append(Arrays.toString(allFrames));
            fw.append("\nallSigma1:\n");
            fw.append(Arrays.toString(allSigma1s));
            fw.append("\nallSigma2:\n");
            fw.append(Arrays.toString(allSigma2s));
            fw.close();
        } catch(Exception ex) {
            IJ.handleException(ex);
        }
    }
}
