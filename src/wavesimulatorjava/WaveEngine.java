/*
 * The MIT License
 *
 * Copyright 2018 Mustafa Sami Salt <https://github.com/mss1451>.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package wavesimulatorjava;

import java.util.ArrayList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WaveEngine {

    private final int MAX_NUMBER_OF_THREADS = 32;
    private final int MAX_NUMBER_OF_OSCILLATORS = 9;

    // "vd" stands for "vertex data"
    double vd[]; // Height map
    double vdv[]; // Velocity map
    double vdl[]; // Loss map
    double vdm[]; // Mass map
    double loss = 0.0; // Reduces mechanical energy (potential + kinetic) by the (100 * loss) percent.
    byte vd_static[]; // Static particle map. Particles which will act like a obstacle or wall.

    boolean osc_active[]; // Oscillator turned on/off?
    OscillatorSource osc_source[]; // Whether to emit from a point or line, or move it from location-1 to location-2.
    double osc_period[]; // Period of oscillator in iterations per cycle.
    double osc_phase[]; // Phase of oscillator in radians.
    double osc_amplitude[]; // Amplitude of oscillator.
    double osc_move_period[]; // Move period of oscillator in iterations per cycle.
    int osc_locations[][]; // Oscillator location set.
    int osc_locations_size[]; // For each oscillator, how many index values are there?
    Point osc_location1_p[]; // Cache location in different types to avoid frequent casting.
    Point osc_location2_p[];

    double FPS = 25; // Frames per second limiter. No limit if zero but might be very expensive so not recommended.
    double IPS = 100; // Iterations per second limiter. No limit if zero but results in CPU overuse and inconsistent speed.
    int calcCounter = 0; // Global calculation counter for oscillators.
    int calcDone = 0; // How many calculations have been done so far?
    int paintDone = 0; // How many paintings have been done so far?
    int numOfThreads = 1; // Number of co-threads. Should be equal to the number of CPU cores for the best performance.
    int TDelay = 5; // Sleep delay for the Main Thread at pause in ms.
    Thread MainT; // Main thread that will generate and run 'numOfThreads' co-threads.
    Thread coThreads[]; // The engine will benefit from all of the cores of CPU efficiently with these co-threads.
    ReentrantLock mutex; // For properties and general access restrictions.
    ReentrantLock mEndMutex[]; // 'I have completed my mission' signal from co-thread.
    Condition mEndCond[]; // 'I have completed my mission' signal from co-thread.
    ReentrantLock mStartMutex; // 'You have new mission' signal from main thread.
    Condition mStartCond; // 'You have new mission' signal from main thread.
    ReentrantLock mCout; // Prevent simultaneous calls to std::cout
    CoThreadMission ctMission = CoThreadMission.Pause; // Defines the mission for each co-thread.
    CoThreadRange ctRange[]; // Data argument for co-threads that define their working range.
    boolean ctDone[]; // True if a co-thread's mission is complete.
    boolean renderEnabled = true; // False = Halt render callback including the painting calculations.
    boolean calculationEnabled = true; // False = Halt calculations.

    boolean locked = false; // Is the pool locked externally for data access?

    boolean logPerformance = true; // Log iteration and paint performances.
    int performanceLogInterval = 1000; // milliseconds

    // Don't stop working but put the threads on sleep more frequently to prevent extreme CPU usage.
    // Decreases performance and accuracy of limiters especially at high iterations-per-second values.
    boolean powerSaveMode = false;

    RenderEventListener renderEventListener; // Render callback function.

    byte bitmap_data[]; // Color data that is passed to the callback function.
    // NOT NEEDED? //void * extra_data; // Extra data that is passed to the callback function (such as canvas class).

    boolean extremeContrastEnabled = false; // There will be only three colors if true: (A+B)/2 for natural, A for crest, and B for trough.
    int amplitudeMultiplier = 20; // Multiplied by height at painting stage to reveal weaker vibrations.

    Color crestColor;
    Color troughColor;
    Color staticColor;

    boolean massMap = false; // Display mass map.
    double massMapRangeHigh = 5.0; // Maximum value for coloring of mass regions.
    double massMapRangeLow = 1.0; // Minimum value for coloring of mass regions.

    boolean work_now = false; // True = Thread must make calculations now, False = Thread must sleep now.

    boolean shifting = true; // True = Shift particles to origin.

    boolean disposing = false; // It will be true once the termination starts.

    int size = 300; // Size of the wave pool. It indicates both the width and height since the pool will always be a square.
    int sizesize = size * size; // Just for further optimization
    double sizesized = size * size; // Just for further optimization

    // These variables are used for absorber. It is used for eliminating reflection from window boundaries.
    int absorb_offset = 25; // Offset from each window boundary where the loss starts to increase.
    double max_loss = 0.3; // The highest loss value. They are located at the boundaries.
    boolean absorberEnabled = true; // If true, the particles near the boundaries will have high loss.

    public WaveEngine() {

        vd = new double[sizesize];
        vdv = new double[sizesize];
        vd_static = new byte[sizesize];
        vdl = new double[sizesize];
        vdm = new double[sizesize];
        bitmap_data = new byte[(sizesize * 3)];

        for (int i = 0; i < sizesize; i++) {
            vdm[i] = 1.0;
        }

        ctDone = new boolean[MAX_NUMBER_OF_THREADS];
        coThreads = new Thread[MAX_NUMBER_OF_THREADS];
        ctRange = new CoThreadRange[MAX_NUMBER_OF_THREADS];
        mutex = new ReentrantLock();
        mStartMutex = new ReentrantLock();
        mCout = new ReentrantLock();
        mEndMutex = new ReentrantLock[MAX_NUMBER_OF_THREADS];
        for (int i = 0; i < MAX_NUMBER_OF_THREADS; i++) {
            mEndMutex[i] = new ReentrantLock();
        }
        mStartCond = mStartMutex.newCondition();
        mEndCond = new Condition[MAX_NUMBER_OF_THREADS];
        for (int i = 0; i < MAX_NUMBER_OF_THREADS; i++) {
            mEndCond[i] = mEndMutex[i].newCondition();
        }

        osc_active = new boolean[MAX_NUMBER_OF_OSCILLATORS];
        osc_source = new OscillatorSource[MAX_NUMBER_OF_OSCILLATORS];
        for (int i = 0; i < MAX_NUMBER_OF_OSCILLATORS; i++) {
            osc_source[i] = OscillatorSource.PointSource;
        }
        osc_period = new double[MAX_NUMBER_OF_OSCILLATORS];
        for (int i = 0; i < MAX_NUMBER_OF_OSCILLATORS; i++) {
            osc_period[i] = 30.0;
        }
        osc_phase = new double[MAX_NUMBER_OF_OSCILLATORS];
        osc_amplitude = new double[MAX_NUMBER_OF_OSCILLATORS];
        for (int i = 0; i < MAX_NUMBER_OF_OSCILLATORS; i++) {
            osc_amplitude[i] = 1.0;
        }
        osc_move_period = new double[MAX_NUMBER_OF_OSCILLATORS];
        for (int i = 0; i < MAX_NUMBER_OF_OSCILLATORS; i++) {
            osc_move_period[i] = 800.0;
        }
        osc_locations = new int[MAX_NUMBER_OF_OSCILLATORS][];
        osc_locations_size = new int[MAX_NUMBER_OF_OSCILLATORS];
        osc_location1_p = new Point[MAX_NUMBER_OF_OSCILLATORS];
        for (int i = 0; i < MAX_NUMBER_OF_OSCILLATORS; i++) {
            osc_location1_p[i] = new Point();
        }
        osc_location2_p = new Point[MAX_NUMBER_OF_OSCILLATORS];
        for (int i = 0; i < MAX_NUMBER_OF_OSCILLATORS; i++) {
            osc_location2_p[i] = new Point();
        }

        crestColor = new Color((byte) 0xFF, (byte) 0xFF, (byte) 0xFF);
        troughColor = new Color((byte) 0, (byte) 0, (byte) 0);
        staticColor = new Color((byte) 0xFF, (byte) 0xFF, (byte) 0);

        setPool(size);
        setCoThreads(0);
        MainT = new Thread(() -> {
            long time_current, time_log_previous, time_start;
            time_start = time_log_previous = System.nanoTime();
            double numOfCalcs = 0; // For statistics, how many calculations have been done so far?
            double numOfPaints = 0; // For statistics, how many paintings have been done so far?
            int calcNeeded = 0; // How many calculations should have been done since beginning?
            int paintNeeded = 0; // How many paintings should have been done since beginning?
            while (!disposing) {

                while (work_now) {

                    if (work_now && calculationEnabled) {
                        time_current = System.nanoTime();
                        if ((IPS == 0 || (calcNeeded = (int) (IPS * (time_current - time_start) / 1E9)) > calcDone)) {
                            mutex.lock();
                            try {
                                sendOrderToCT(CoThreadMission.CalculateForces);
                                waitForCT();
                                sendOrderToCT(CoThreadMission.MoveParticles);
                                waitForCT();

                                if (shifting) {
                                    shiftToOrigin();
                                }
                                numOfCalcs++;
                                calcDone++;
                                calcCounter++;
                                if (calcNeeded > calcDone + 1) {
                                    calcDone = calcNeeded - 1;
                                }
                            } catch (InterruptedException ex) {
                                Logger.getLogger(WaveEngine.class.getName()).log(Level.SEVERE, null, ex);
                            } finally {
                                mutex.unlock();
                            }
                        }

                    }

                    if (work_now && renderEnabled) {
                        time_current = System.nanoTime();
                        if (FPS == 0
                                || (paintNeeded
                                = (int) (FPS * (time_current - time_start) / 1E9)) > paintDone) {
                            mutex.lock();
                            try {
                                sendOrderToCT(CoThreadMission.CalculateColors);
                                waitForCT();

                                numOfPaints++;
                                paintDone++;
                                if (paintNeeded > paintDone + 1) {
                                    paintDone = paintNeeded - 1;
                                }
                            } catch (InterruptedException ex) {
                                Logger.getLogger(WaveEngine.class.getName()).log(Level.SEVERE, null, ex);
                            } finally {
                                mutex.unlock();
                            }
                            if (renderEventListener != null) {
                                renderEventListener.onRenderEvent(bitmap_data);
                            }
                        }

                    }

                    if (work_now) {
                        time_current = System.nanoTime();
                        if ((time_current - time_log_previous) / 1E6
                                >= performanceLogInterval) {
                            time_log_previous = System.nanoTime();
                            double perf_interval
                                    = performanceLogInterval;
                            mCout.lock();
                            try {
                                System.out.print(
                                        "Iterations & Paints per second:	"
                                        + 1000.0 / (perf_interval / numOfCalcs) + "	"
                                        + 1000.0 / (perf_interval / numOfPaints)
                                        + "\n");
                                numOfCalcs = 0;
                                numOfPaints = 0;
                            } finally {
                                mCout.unlock();
                            }

                        }
                    }
                    if ((!powerSaveMode
                            && (FPS == 0 || IPS == 0))
                            || (calculationEnabled
                            && calcDone < calcNeeded)
                            || (renderEnabled
                            && paintDone < paintNeeded)) {
                        // In a hurry
                        Thread.yield();
                    } else {
                        mutex.lock();
                        try {
                            sendOrderToCT(CoThreadMission.Pause);
                        } finally {
                            mutex.unlock();
                        }
                        if (powerSaveMode) {
                            try {
                                Thread.sleep(TDelay);
                            } catch (InterruptedException ex) {
                                Logger.getLogger(WaveEngine.class.getName()).log(Level.SEVERE, null, ex);
                            }
                        } else {
                            Thread.yield();
                        }
                    }

                }

                try {
                    Thread.sleep(TDelay);
                } catch (InterruptedException ex) {
                    Logger.getLogger(WaveEngine.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        });
        MainT.setName("main");
        MainT.start();
    }

    public void dispose() {
        try {
            disposing = true;
            work_now = false;

            mutex.lock();
            try {
                sendOrderToCT(CoThreadMission.Destroy);
                for (int i = 0; i < numOfThreads; i++) {
                    coThreads[i].join();
                }
            } catch (InterruptedException ex) {
                Logger.getLogger(WaveEngine.class.getName()).log(Level.SEVERE, null, ex);
            } finally {
                mutex.unlock();
            }
            MainT.join();

        } catch (InterruptedException ex) {
            Logger.getLogger(WaveEngine.class.getName()).log(Level.SEVERE, null, ex);
        }

    }

    private int clamp(int x, int low, int high) {
        return (((x) > (high)) ? (high) : (((x) < (low)) ? (low) : (x)));
    }

    private double clamp(double x, double low, double high) {
        return (((x) > (high)) ? (high) : (((x) < (low)) ? (low) : (x)));
    }

    private void sendOrderToCT(CoThreadMission order) {
        ctMission = order;
        for (int i = 0; i < numOfThreads; i++) {
            mEndMutex[i].lock();
            try {
                ctDone[i] = false;
            } finally {
                mEndMutex[i].unlock();
            }
        }
        mStartMutex.lock();
        try {
            mStartCond.signalAll();
        } finally {
            mStartMutex.unlock();
        }
    }

    private void waitForCT() throws InterruptedException {
        for (int i = 0; i < numOfThreads; i++) {
            mEndMutex[i].lock();
            try {
                while (work_now && !ctDone[i]) {
                    mEndCond[i].awaitNanos((long) (3 * 1E9));

                }
            } finally {
                mEndMutex[i].unlock();
            }
        }
    }

    public boolean getOscillatorEnabled(int oscillatorId) {
        if (oscillatorId >= 0 && oscillatorId < MAX_NUMBER_OF_OSCILLATORS) {
            return osc_active[oscillatorId];
        } else {
            return false;
        }

    }

    public void setOscillatorEnabled(int oscillatorId, boolean enabled) {
        if (oscillatorId >= 0 && oscillatorId < MAX_NUMBER_OF_OSCILLATORS) {
            mutex.lock();
            try {
                osc_active[oscillatorId] = enabled;
            } finally {
                mutex.unlock();
            }
        }
    }

    public OscillatorSource getOscillatorSource(int oscillatorId) {
        if (oscillatorId >= 0 && oscillatorId < MAX_NUMBER_OF_OSCILLATORS) {
            return osc_source[oscillatorId];
        } else {
            return OscillatorSource.PointSource;
        }
    }

    public void setOscillatorSource(int oscillatorId,
            OscillatorSource oscillatorSource) {
        if (oscillatorId >= 0 && oscillatorId < MAX_NUMBER_OF_OSCILLATORS) {
            mutex.lock();
            try {
                osc_source[oscillatorId] = oscillatorSource;
                updateOscLocIndices(oscillatorId);
            } finally {
                mutex.unlock();
            }
        }
    }

    public double getOscillatorPeriod(int oscillatorId) {
        if (oscillatorId >= 0 && oscillatorId < MAX_NUMBER_OF_OSCILLATORS) {
            return osc_period[oscillatorId];
        } else {
            return -1;
        }
    }

    public void setOscillatorPeriod(int oscillatorId, double period) {
        if (oscillatorId >= 0 && oscillatorId < MAX_NUMBER_OF_OSCILLATORS && period >= 1) {
            mutex.lock();
            try {
                osc_period[oscillatorId] = period;
            } finally {
                mutex.unlock();
            }
        }
    }

    public double getOscillatorPhase(int oscillatorId) {
        if (oscillatorId >= 0 && oscillatorId < MAX_NUMBER_OF_OSCILLATORS) {
            return osc_phase[oscillatorId];
        } else {
            return -1;
        }
    }

    public void setOscillatorPhase(int oscillatorId, double phase) {
        if (oscillatorId >= 0 && oscillatorId < MAX_NUMBER_OF_OSCILLATORS) {
            mutex.lock();
            try {
                osc_phase[oscillatorId] = phase;
            } finally {
                mutex.unlock();
            }
        }
    }

    public double getOscillatorAmplitude(int oscillatorId) {
        if (oscillatorId >= 0 && oscillatorId < MAX_NUMBER_OF_OSCILLATORS) {
            return osc_amplitude[oscillatorId];
        } else {
            return -1;
        }
    }

    public void setOscillatorAmplitude(int oscillatorId,
            double amplitude) {
        if (oscillatorId >= 0 && oscillatorId < MAX_NUMBER_OF_OSCILLATORS) {
            mutex.lock();
            try {
                osc_amplitude[oscillatorId] = amplitude;
            } finally {
                mutex.unlock();
            }
        }
    }

    public double getOscillatorMovePeriod(int oscillatorId) {
        if (oscillatorId >= 0 && oscillatorId < MAX_NUMBER_OF_OSCILLATORS) {
            return osc_move_period[oscillatorId];
        } else {
            return -1;
        }
    }

    public void setOscillatorMovePeriod(int oscillatorId,
            double movePeriod) {
        if (oscillatorId >= 0 && oscillatorId < MAX_NUMBER_OF_OSCILLATORS && movePeriod >= 1) {
            mutex.lock();
            try {
                osc_move_period[oscillatorId] = movePeriod;
            } finally {
                mutex.unlock();
            }
        }
    }

    public Point getOscillatorLocation(int oscillatorId,
            int locationId) {
        if (oscillatorId >= 0 && oscillatorId < MAX_NUMBER_OF_OSCILLATORS) {
            switch (locationId) {
                case 0:
                    return osc_location1_p[oscillatorId];
                case 1:
                    return osc_location2_p[oscillatorId];
                default:
                    return new Point(-1, -1);
            }
        } else {
            return new Point(-1, -1);
        }

    }

    public void setOscillatorLocation(int oscillatorId,
            int locationId, Point location) {
        if (oscillatorId >= 0 && oscillatorId < MAX_NUMBER_OF_OSCILLATORS && locationId <= 1) {
            mutex.lock();
            try {
                if (locationId == 0) {
                    osc_location1_p[oscillatorId] = location;
                } else {
                    osc_location2_p[oscillatorId] = location;
                }

                updateOscLocIndices(oscillatorId);
            } finally {
                mutex.unlock();
            }
        }

    }

    public Point getOscillatorRealLocation(int oscillatorId) {
        if (oscillatorId >= 0 && oscillatorId < MAX_NUMBER_OF_OSCILLATORS) {
            double ratio_1;
            switch (osc_source[oscillatorId]) {
                case PointSource:
                    return osc_location1_p[oscillatorId];
                case LineSource:
                    return new Point(
                            (osc_location1_p[oscillatorId].x
                            + osc_location2_p[oscillatorId].x) / 2,
                            (osc_location1_p[oscillatorId].y
                            + osc_location2_p[oscillatorId].y) / 2);
                case MovingPointSource:
                    ratio_1 = (calcCounter % osc_move_period[oscillatorId])
                            / osc_move_period[oscillatorId];
                    return new Point(
                            (1.0 - ratio_1) * osc_location1_p[oscillatorId].x
                            + ratio_1 * osc_location2_p[oscillatorId].x,
                            (1.0 - ratio_1) * osc_location1_p[oscillatorId].y
                            + ratio_1 * osc_location2_p[oscillatorId].y);
                default:
                    return new Point(-1, -1);
            }
        } else {
            return new Point(-1, -1);
        }
    }

    public double getLossRatio() {
        return loss;
    }

    public void setLossRatio(double loss) {
        mutex.lock();
        try {
            this.loss = clamp(loss, 0.0, 1.0);
            setLossRatio();
        } finally {
            mutex.unlock();
        }
    }

    public double getFramesPerSecond() {
        return FPS;
    }

    public void setFramesPerSecond(double framesPerSecond) {
        mutex.lock();
        try {
            this.FPS = clamp(framesPerSecond, 0, framesPerSecond);
            paintDone = 0;
        } finally {
            mutex.unlock();
        }
    }

    public double getIterationsPerSecond() {
        return IPS;
    }

    public void setIterationsPerSecond(double iterationsPerSecond) {
        mutex.lock();
        try {
            this.IPS = clamp(iterationsPerSecond, 0, iterationsPerSecond);
            calcDone = 0;
        } finally {
            mutex.unlock();
        }
    }

    public int getNumberOfThreads() {
        return numOfThreads;
    }

    public void setNumberOfThreads(int numberOfThreads) {
        mutex.lock();
        try {
            int oldNumOfThreads = numOfThreads;
            this.numOfThreads = clamp(numberOfThreads, 1, MAX_NUMBER_OF_THREADS);
            setCoThreads(oldNumOfThreads);
        } finally {
            mutex.unlock();
        }
    }

    public int getThreadDelay() {
        return TDelay;
    }

    public void setThreadDelay(int threadDelay) {
        mutex.lock();
        try {
            this.TDelay = clamp(threadDelay, 0, 1000);
        } finally {
            mutex.unlock();
        }
    }

    public boolean getRenderEnabled() {
        return renderEnabled;
    }

    public void setRenderEnabled(boolean renderEnabled) {
        mutex.lock();
        try {
            this.renderEnabled = renderEnabled;
        } finally {
            mutex.unlock();
        }
    }

    public boolean getCalculationEnabled() {
        return calculationEnabled;
    }

    public void setCalculationEnabled(boolean calculationEnabled) {
        mutex.lock();
        try {
            this.calculationEnabled = calculationEnabled;
        } finally {
            mutex.unlock();
        }
    }

    public boolean getLogPerformance() {
        return logPerformance;
    }

    public void setLogPerformance(boolean logPerformance) {
        mutex.lock();
        try {
            this.logPerformance = logPerformance;
        } finally {
            mutex.unlock();
        }
    }

    public boolean getPowerSaveMode() {
        return powerSaveMode;
    }

    public void setPowerSaveMode(boolean powerSaveMode) {
        mutex.lock();
        try {
            this.powerSaveMode = powerSaveMode;
        } finally {
            mutex.unlock();
        }
    }

    public int getPerformanceLogInterval() {
        return performanceLogInterval;
    }

    public void setPerformanceLogInterval(
            int performanceLogInterval) {
        mutex.lock();
        try {
            this.performanceLogInterval = clamp(performanceLogInterval, 0,
                    performanceLogInterval);
        } finally {
            mutex.unlock();
        }
    }

    public double getMassMapRangeHigh() {
        return massMapRangeHigh;
    }

    public void setMassMapRangeHigh(double massMapRangeHigh) {
        mutex.lock();
        try {
            this.massMapRangeHigh = clamp(massMapRangeHigh, 0, massMapRangeHigh);
        } finally {
            mutex.unlock();
        }
    }

    public double getMassMapRangeLow() {
        return massMapRangeLow;
    }

    public void setMassMapRangeLow(double massMapRangeLow) {
        mutex.lock();
        try {
            this.massMapRangeLow = clamp(massMapRangeLow, 0, massMapRangeLow);
        } finally {
            mutex.unlock();
        }
    }

    public boolean getShowMassMap() {
        return massMap;
    }

    public void setShowMassMap(boolean showMassMap) {
        mutex.lock();
        try {
            this.massMap = showMassMap;
        } finally {
            mutex.unlock();
        }
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        mutex.lock();
        try {
            int oldsize = this.size;
            this.size = clamp(size, 1, size);
            sizesize = size * size;
            sizesized = sizesize;
            setPool(oldsize);
            setCoThreads(numOfThreads);
        } finally {
            mutex.unlock();
        }
    }

    public double getAbsorberLossRatio() {
        return max_loss;
    }

    public void setAbsorberLossRatio(double absorberLoss) {
        mutex.lock();
        try {
            this.max_loss = clamp(absorberLoss, 0, 1.0);
            setLossRatio();
        } finally {
            mutex.unlock();
        }
    }

    public int getAbsorberThickness() {
        return absorb_offset;
    }

    public void setAbsorberThickness(int absorberThickness) {
        mutex.lock();
        try {
            this.absorb_offset = clamp(absorberThickness, 0, size / 2);
            setLossRatio();
        } finally {
            mutex.unlock();
        }
    }

    public boolean getShiftParticlesEnabled() {
        return shifting;
    }

    public void setShiftParticlesEnabled(boolean shiftParticlesEnabled) {
        mutex.lock();
        try {
            this.shifting = shiftParticlesEnabled;
        } finally {
            mutex.unlock();
        }
    }

    public boolean getAbsorberEnabled() {
        return absorberEnabled;
    }

    public void setAbsorberEnabled(boolean absorberEnabled) {
        mutex.lock();
        try {
            this.absorberEnabled = absorberEnabled;
            setLossRatio();
        } finally {
            mutex.unlock();
        }
    }

    public RenderEventListener getRenderEventListener() {
        return renderEventListener;
    }

    public void setRenderEventListener(RenderEventListener renderEventListener) {
        mutex.lock();
        try {
            this.renderEventListener = renderEventListener;
        } finally {
            mutex.unlock();
        }
    }

    public boolean getExtremeContrastEnabled() {
        return extremeContrastEnabled;
    }

    public void setExtremeContrastEnabled(boolean extremeContrastEnabled) {
        mutex.lock();
        try {
            this.extremeContrastEnabled = extremeContrastEnabled;
        } finally {
            mutex.unlock();
        }
    }

    public int getAmplitudeMultiplier() {
        return amplitudeMultiplier;
    }

    public void setAmplitudeMultiplier(int amplitudeMultiplier) {
        mutex.lock();
        try {
            this.amplitudeMultiplier = clamp(amplitudeMultiplier, 0, amplitudeMultiplier);
        } finally {
            mutex.unlock();
        }
    }

    public Color getCrestColor() {
        return crestColor;
    }

    public void setCrestColor(Color crestColor) {
        mutex.lock();
        try {
            this.crestColor = crestColor;
        } finally {
            mutex.unlock();
        }
    }

    public Color getTroughColor() {
        return troughColor;
    }

    public void setTroughColor(Color troughColor) {
        mutex.lock();
        try {
            this.troughColor = troughColor;
        } finally {
            mutex.unlock();
        }
    }

    public Color getStaticColor() {
        return staticColor;
    }

    public void setStaticColor(Color staticColor) {
        mutex.lock();
        try {
            this.staticColor = staticColor;
        } finally {
            mutex.unlock();
        }
    }

    public boolean lock() {
        if (locked) {
            return false;
        }
        locked = true;
        mutex.lock();
        return true;
    }

    public boolean unlock() {
        mutex.unlock();
        locked = false;
        return true;
    }

    public Object getData(ParticleAttribute particleAttribute) {
        if (!locked) {
            return null;
        }
        switch (particleAttribute) {
            case Fixity:
                return vd_static;
            case Loss:
                return vdl;
            case Height:
                return vd;
            case Mass:
                return vdm;
            case Velocity:
                return vdv;
            default:
                return null;
        }
    }

    public void start() {
        mutex.lock();
        try {
            work_now = true;
        } finally {
            mutex.unlock();
        }
    }

    public void stop() {
        mutex.lock();
        try {
            work_now = false;
        } finally {
            mutex.unlock();
        }
    }

    public boolean isWorking() {
        return work_now;
    }

    private boolean calculateForces(final int firstIndex,
            final int count) {

        // Check if the parameters are valid.
        if (firstIndex < 0 || firstIndex + count > sizesize || count < 1) {
            return false;
        }

        final int fplusc = firstIndex + count;
        for (int index = firstIndex; index < fplusc; index++) {
            // If this is a static particle, it will not move at all. Continue with the next particle.
            if (vd_static[index] != 0) {
                vd[index] = 0;
                continue;
            }

            // We will find out the average height of the 8 neighbor particles.
            // So that we will know where the current particle will be attracted to.
            // "heights" is the sum of all the height values of neighbor particles.
            double heights = 0.0;
            final double iplus1modsz = (index + 1) % size;
            final double imodsz = index % size;
            // "num_of_part" is the number of particles which contributed to the "heights".
            double num_of_parts = 0.0;

            boolean up_exists = false, left_exists = false, right_exists = false;

            if (index >= size && vd_static[index - size] == 0) {
                up_exists = true;
                heights += vd[index - size];
                num_of_parts++;
            }

            if (iplus1modsz != 0 && vd_static[index + 1] == 0) {
                right_exists = true;
                heights += vd[index + 1];
                num_of_parts++;
                if (up_exists && vd_static[index - size + 1] == 0) {
                    heights += vd[index - size + 1];
                    num_of_parts++;
                }
            }

            if (imodsz != 0 && vd_static[index - 1] == 0) {
                left_exists = true;
                heights += vd[index - 1];
                num_of_parts++;
                if (up_exists && vd_static[index - size - 1] == 0) {
                    heights += vd[index - size - 1];
                    num_of_parts++;
                }
            }

            if (index < sizesize - size && vd_static[index + size] == 0) {
                heights += vd[index + size];
                num_of_parts++;
                if (left_exists && vd_static[index + size - 1] == 0) {
                    heights += vd[index + size - 1];
                    num_of_parts++;
                }
                if (right_exists && vd_static[index + size + 1] == 0) {
                    heights += vd[index + size + 1];
                    num_of_parts++;
                }
            }

            double acceleration = 0.0;
            double height_difference = 0.0;

            if (num_of_parts != 0) {
                heights /= num_of_parts;
                height_difference = vd[index] - heights;
                acceleration = -height_difference / vdm[index];
            }
            // Keep velocity under a velocity which will prevent
            // violation of conservation energy and chaotic noise.
            if (acceleration >= 0)
                acceleration = clamp(acceleration, acceleration, -height_difference * 2);
            else
                acceleration = clamp(acceleration, -height_difference * 2, acceleration);
            // Acceleration feeds velocity.
            vdv[index] += acceleration;

            // Loss takes place.
            // Reduce the kinetic energy. The kinetic energy is 0.5 times mass times velocity squared.
            // With this equation, we can derive the velocity for the reduced kinetic energy.
            double kinetic_energy = (0.5 * vdm[index] * Math.pow(vdv[index], 2));
            // Multiply the energy with one minus loss ratio and find the velocity for that kinetic energy.
            // Leaving the velocity on the left side results in the following.
            vdv[index] = Math.sqrt(2 * kinetic_energy * (1.0 - vdl[index]) / vdm[index])
                    * Math.signum(vdv[index]);

            // Reduce the potential energy. The potential energy for this model is 0.5 times height difference squared.
            // With this equation, we can derive the height for the reduced potential energy.
            double potential_energy = (0.5 * Math.pow(height_difference, 2.0));
            // Multiply the energy with one minus loss ratio and find the height for that potential energy.
            // Leaving the height difference on the left side results in the following.
            vd[index] += Math.sqrt(2 * potential_energy * (1.0 - vdl[index]))
                    * Math.signum(height_difference) - height_difference;
        }

        // Process oscillators
        for (int i = 0; i < MAX_NUMBER_OF_OSCILLATORS; i++) {
            if (osc_active[i]) {
                double osc_height = osc_amplitude[i]
                        * Math.sin(
                                osc_phase[i] * Math.PI / 180.0
                                + 2.0 * Math.PI
                                * (calcCounter % osc_period[i])
                                / osc_period[i]);

                switch (osc_source[i]) {
                    case PointSource:
                        vd[osc_locations[i][0]] = osc_height;
                        vdv[osc_locations[i][0]] = 0;
                        break;
                    case LineSource:
                        for (int j = 0; j < osc_locations_size[i]; j++) {
                            vd[osc_locations[i][j]] = osc_height;
                            vdv[osc_locations[i][j]] = 0;
                        }
                        break;
                    case MovingPointSource:
                        double ratio_1 = (calcCounter % osc_move_period[i])
                                / osc_move_period[i];
                        Point cur_point = new Point(
                                (1.0 - ratio_1) * osc_location1_p[i].x
                                + ratio_1 * osc_location2_p[i].x,
                                (1.0 - ratio_1) * osc_location1_p[i].y
                                + ratio_1 * osc_location2_p[i].y);
                        int cur_index = (int) cur_point.x
                                + size * (int) cur_point.y;
                        vd[cur_index] = osc_height;
                        vdv[cur_index] = 0;
                        break;
                }

            }
        }
        return true;
    }

    private void moveParticles(final int firstIndex,
            final int count) {
        int fplusc = firstIndex + count;
        // Check if the parameters are valid
        if (firstIndex < 0 || count < 1 || firstIndex > sizesize || fplusc > sizesize) {
            return;
        }

        for (int index = firstIndex; index < fplusc; index++) {

            /*if (vd[index] + vdv[index] > 1.0)
		 vd[index] = 1.0;
		 else if (vd[index] + vdv[index] <= 1.0
		 && vd[index] + vdv[index] >= -1.0)
		 vd[index] += vdv[index]; // Velocity feeds height.
		 else
		 vd[index] = -1.0;*/
            vd[index] += vdv[index]; // Velocity feeds height.
        }
    }

    private void shiftToOrigin() {
        double total_height = 0; // This will be used to shift the height center of the whole particle system to the origin.
        // Sum up all the height values so we will find the average height of the system.
        for (int i = 0; i < sizesize; i++) //for (double height : vd)
        {
            total_height += vd[i];
        }

        // Origin height is zero. So "shifting" is the distance between the system average height and the origin.
        double shift_amount = -total_height / sizesized;

        // Here is the last step on shifting the whole system to the origin point.
        for (int i = 0; i < sizesize; i++) {
            vd[i] += shift_amount;
        }
    }

    private void setLossRatio() {
        if (absorberEnabled) {
            // We will fill "vdl" array with "loss" then we will deal with elements near to window boundaries.

            // Since we want the loss to increase towards the edges, "max_loss" can't be smaller than "loss".
            if (max_loss < loss) {
                // The only thing to do is to fill "vdf" array with "loss" in this case.
                for (int i = 0; i < sizesize; i++) {
                    vdl[i] = loss;
                }
                return;
            }

            // loss gain fields should not mix with each other. So the maximum offset is the middle-screen.
            if (absorb_offset >= size / 2) {
                absorb_offset = size / 2 - 1;
            }

            // This value is loss increasion rate per row/column. The increasion is linear.
            double dec = (max_loss - loss) / absorb_offset;
            // This one stores the current loss.
            double cur = max_loss;

            // First, we fill "vdl" array with "loss".
            for (int i = 0; i < sizesize - 1; i++) {
                vdl[i] = loss;
            }

            // This loop sets up the loss values for the top.
            for (int off = 0; off <= absorb_offset; off++) {
                // Process each row/column from the edge to the offset.
                for (int x = off; x < size - off; x++) {
                    // Process each loss element in the current row/column
                    vdl[x + off * size] = cur;
                }
                cur -= dec;
            }

            cur = loss; // Reset the current loss.

            // This loop sets up the loss values for the bottom.
            for (int off = 0; off <= absorb_offset; off++) {
                for (int x = absorb_offset - off;
                        x < size - (absorb_offset - off); x++) {
                    vdl[x + off * size + size * (size - absorb_offset - 1)] = cur;
                }
                cur += dec;
            }

            cur = loss;

            // This loop sets up the loss values for the left.
            for (int off = 0; off <= absorb_offset; off++) {
                for (int x = absorb_offset - off;
                        x < size - (absorb_offset - off); x++) {
                    vdl[x * size + (absorb_offset - off)] = cur;
                }
                cur += dec;
            }

            cur = loss;

            // This loop sets up the loss values for the right.
            for (int off = 0; off <= absorb_offset; off++) {
                for (int x = absorb_offset - off;
                        x < size - (absorb_offset - off); x++) {
                    vdl[x * size + off + size - absorb_offset - 1] = cur;
                }
                cur += dec;
            }
        } else {
            // The only thing to do is to fill "vdl" array with "loss" in this case.
            for (int i = 0; i < sizesize; i++) {
                vdl[i] = loss;
            }
        }
    }

    public static int ubyte(byte b) {
        return b & 0xFF;
    }

    private boolean paintBitmap(final int firstIndex,
            final int count, byte[] rgbdata) {
        // Check if the parameters are valid
        if (firstIndex > 0 || count < 1 || firstIndex + count > sizesize || firstIndex > sizesize) {
            return false;
        }

        // Render the region that is associated with this thread.
        for (int index = firstIndex; index < firstIndex + count; index++) {
            if (!massMap) {

                if (vd_static[index] != 0) {

                    rgbdata[index * 3] = staticColor.r;
                    rgbdata[index * 3 + 1] = staticColor.g;
                    rgbdata[index * 3 + 2] = staticColor.b;

                } else {
                    // This value is the 'brightness' of the height.

                    if (extremeContrastEnabled) {
                        if (vd[index] > 0) {

                            rgbdata[index * 3] = crestColor.r;
                            rgbdata[index * 3 + 1] = crestColor.g;
                            rgbdata[index * 3 + 2] = crestColor.b;

                        } else if (vd[index] < 0) {

                            rgbdata[index * 3] = troughColor.r;
                            rgbdata[index * 3 + 1] = troughColor.g;
                            rgbdata[index * 3 + 2] = troughColor.b;

                        } else if (vd[index] == 0) {

                            rgbdata[index * 3] = (byte) ((ubyte(crestColor.r)
                                    + ubyte(troughColor.r)) / 2.0);
                            rgbdata[index * 3 + 1] = (byte) ((ubyte(crestColor.g)
                                    + ubyte(troughColor.g)) / 2.0);
                            rgbdata[index * 3 + 2] = (byte) ((ubyte(crestColor.b)
                                    + ubyte(troughColor.b)) / 2.0);

                        }

                    } else {
                        double brightr1 = (clamp(vd[index] * amplitudeMultiplier, -1.0, 1.0) + 1) / 2;
                        double brightr2 = 1.0 - brightr1;

                        rgbdata[index * 3] = (byte) (ubyte(crestColor.r) * brightr1
                                + ubyte(troughColor.r) * brightr2);
                        rgbdata[index * 3 + 1] = (byte) (ubyte(crestColor.g) * brightr1
                                + ubyte(troughColor.g) * brightr2);
                        rgbdata[index * 3 + 2] = (byte) (ubyte(crestColor.b) * brightr1
                                + ubyte(troughColor.b) * brightr2);
                    }
                }

            } else {
                /* RGB route (linear transition)

			 R		 G		 B
			 --------------------
			 0.0		0.0		0.0
			 0.0		0.0		0.5
			 0.5		0.0		0.0
			 1.0		0.5		0.0
			 1.0		1.0		0.5
			 1.0		1.0		1.0

			 This is similar to thermal image color scale.
			 Yields 128 * 5 - 4 = 636 colors.
                 */
                double massrange = massMapRangeHigh - massMapRangeLow;
                double colors = 128.0 * 5.0 - 4.0; // numFrames * numTrans - (numTrans - 1)
                if (massrange <= 0) {
                    rgbdata[index * 3] = 0;
                    rgbdata[index * 3 + 1] = 0;
                    rgbdata[index * 3 + 2] = 0;
                    return false;
                }
                short color = (short) Math.round(
                        (clamp(vdm[index], massMapRangeLow, massMapRangeHigh)
                        - massMapRangeLow) * colors / massrange);
                if (color < 128) {
                    rgbdata[index * 3] = 0;
                    rgbdata[index * 3 + 1] = 0;
                    rgbdata[index * 3 + 2] = (byte) color;
                } else if (color < 128 * 2) {
                    rgbdata[index * 3] = (byte) (color & 127);
                    rgbdata[index * 3 + 1] = 0;
                    rgbdata[index * 3 + 2] = 127;
                } else if (color < 128 * 3) {
                    rgbdata[index * 3] = (byte) (128 + (color & 127));
                    rgbdata[index * 3 + 1] = (byte) (color & 127);
                    rgbdata[index * 3 + 2] = (byte) (127 - (color & 127));
                } else if (color < 128 * 4) {
                    rgbdata[index * 3] = (byte) 255;
                    rgbdata[index * 3 + 1] = (byte) (128 + (color & 127));
                    rgbdata[index * 3 + 2] = (byte) (color & 127);
                } else if (color < 128 * 5) {
                    rgbdata[index * 3] = (byte) 255;
                    rgbdata[index * 3 + 1] = (byte) 255;
                    rgbdata[index * 3 + 2] = (byte) (128 + (color & 127));
                }
            }
        }
        return true;
    }

    private void setPool(int oldsize) {

        // Old size was required for C++ version but let's use it anyway.
        int oldsizesize = oldsize * oldsize;

        vd = new double[sizesize];

        vdv = new double[sizesize];

        byte vd_static_old[] = new byte[oldsizesize];
        System.arraycopy(vd_static, 0, vd_static_old, 0, oldsizesize);

        vd_static = new byte[sizesize];

        vdl = new double[sizesize];

        double vdm_old[] = new double[oldsizesize];
        System.arraycopy(vdm, 0, vdm_old, 0, oldsizesize);

        vdm = new double[sizesize];

        bitmap_data = new byte[sizesize * 3];

        for (int i = 0; i < sizesize; i++) {
            vdm[i] = 1.0;
        }

        // Resize static & mass map
        double stepsize = (double) oldsize / size;
        double stepsize_db2 = stepsize / 2;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                vd_static[x + size * y] = vd_static_old[(int) Math.floor(
                        x * stepsize + stepsize_db2)
                        + oldsize
                        * (int) Math.floor(
                                y * stepsize + stepsize_db2)];
                vdm[x + size * y] = vdm_old[(int) Math.floor(
                        x * stepsize + stepsize_db2)
                        + oldsize
                        * (int) Math.floor(
                                y * stepsize + stepsize_db2)];
            }
        }

        // Re-locate oscillators
        for (int i = 0; i < MAX_NUMBER_OF_OSCILLATORS; i++) {
            osc_location1_p[i] = new Point(
                    osc_location1_p[i].x * size / oldsize,
                    osc_location1_p[i].y * size / oldsize);

            osc_location2_p[i] = new Point(
                    osc_location2_p[i].x * size / oldsize,
                    osc_location2_p[i].y * size / oldsize);

            updateOscLocIndices(i);
        }

        setLossRatio();

    }

    private void updateOscLocIndices(int oscillatorId) {

        Point p1 = osc_location1_p[oscillatorId];
        Point p2 = osc_location2_p[oscillatorId];
        double length, xoverl, yoverl;
        Point currentPoint;
        ArrayList<Integer> indices = new ArrayList<>();
        switch (osc_source[oscillatorId]) {
            case PointSource:
                if (p1.x >= 0 && p1.x < size && p1.y >= 0 && p1.y < size) {
                    osc_locations_size[oscillatorId] = 1;
                    osc_locations[oscillatorId] = new int[osc_locations_size[oscillatorId]];
                    osc_locations[oscillatorId][0] = (int) p1.x
                            + size * (int) p1.y;
                }
                break;
            case LineSource:

                length = Point.dist(p1, p2);
                if (length == 0) {
                    return;
                }
                xoverl = (p2.x - p1.x) / length;
                yoverl = (p2.y - p1.y) / length;

                for (double i = 0; i < length; i += 0.5) {
                    currentPoint = new Point(p1.x + (xoverl * i), p1.y + (yoverl * i));
                    if (currentPoint.x < size && currentPoint.x >= 0
                            && currentPoint.y < size && currentPoint.y >= 0) {
                        indices.add(
                                (int) Math.floor(currentPoint.x)
                                + size * (int) Math.floor(currentPoint.y));
                    }
                }
                osc_locations[oscillatorId] = null;
                if ((osc_locations_size[oscillatorId] = indices.size()) > 0) {
                    osc_locations[oscillatorId] = new int[osc_locations_size[oscillatorId]];
                    System.arraycopy(indices.stream().mapToInt(Integer::intValue).toArray(),
                            0, osc_locations[oscillatorId], 0, indices.size());
                }
                break;
            case MovingPointSource:
                osc_locations_size[oscillatorId] = 0;
                osc_locations[oscillatorId] = null;
                break;
        }

    }

    private void setCoThreads(int oldNumOfThreads) {

        sendOrderToCT(CoThreadMission.Destroy);

        for (int i = 0; i < oldNumOfThreads; i++) {
            try {
                coThreads[i].join();
            } catch (InterruptedException ex) {
                Logger.getLogger(WaveEngine.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

        ctMission = CoThreadMission.Pause;

        boolean size_plus_one = false;
        int partial_size = sizesize / numOfThreads;
        if (sizesize % numOfThreads > 0) {
            size_plus_one = true;
        }
        int curPart = 0;
        for (int j = 0; j < numOfThreads; j++) {
            if (j == numOfThreads - 1 && size_plus_one) {
                partial_size++;
            }
            ctRange[j] = new CoThreadRange(j, curPart, partial_size);
            final int i = j;

            coThreads[i] = new Thread(() -> {
                mCout.lock();
                try {
                    System.out.print("co-thread[" + i + "] is going into loop\n");
                } finally {
                    mCout.unlock();
                }
                boolean signal_main;

                while (ctMission != CoThreadMission.Destroy && !disposing) {

                    signal_main = false;
                    mEndMutex[i].lock();
                    try {
                        if (!ctDone[i]) {
                            if (null != ctMission) {
                                switch (ctMission) {
                                    case CalculateForces:
                                        calculateForces(ctRange[i].firstIndex,
                                                ctRange[i].count);
                                        signal_main = true;
                                        break;
                                    case MoveParticles:
                                        moveParticles(ctRange[i].firstIndex,
                                                ctRange[i].count);
                                        signal_main = true;
                                        break;
                                    case CalculateColors:
                                        paintBitmap(ctRange[i].firstIndex,
                                                ctRange[i].count, bitmap_data);
                                        signal_main = true;
                                        break;
                                    default:
                                        break;
                                }
                            }

                            if (signal_main) {
                                //std::cout << "signaling main (" << i << ")" << std::endl;

                                ctDone[i] = true;
                                mEndCond[i].signal();

                            }
                        }
                    } finally {
                        mEndMutex[i].unlock();
                    }

                    mStartMutex.lock();
                    try {
                        while (ctMission == CoThreadMission.Pause && !disposing
                                && work_now) {

                            try {
                                mStartCond.awaitNanos((long) 1E9);
                            } catch (InterruptedException ex) {
                                Logger.getLogger(WaveEngine.class.getName()).log(Level.SEVERE, null, ex);
                            }

                        }
                    } finally {
                        mStartMutex.unlock();
                    }

                    if (!work_now) {
                        try {
                            Thread.sleep(TDelay);
                        } catch (InterruptedException ex) {
                            Logger.getLogger(WaveEngine.class.getName()).log(Level.SEVERE, null, ex);
                        }
                    }

                }
                mCout.lock();
                try {
                    System.out.print("co-thread[" + i + "] is returning\n");
                } finally {
                    mCout.unlock();
                }
            });
            coThreads[j].setName("co");
            coThreads[j].start();
            curPart += partial_size;
        }
    }
}
