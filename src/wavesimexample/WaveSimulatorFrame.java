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
package wavesimexample;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import javax.swing.JFrame;
import wavesimulatorjava.OscillatorSource;
import wavesimulatorjava.ParticleAttribute;
import wavesimulatorjava.Point;
import wavesimulatorjava.WaveEngine;

/**
 *
 * @author Mustafa Sami Salt <https://github.com/mss1451>
 */
public class WaveSimulatorFrame extends JFrame implements WindowListener {

    // We need an image/bitmap which holds the visual data of the engine.
    // The data from the engine will be copied to this image's data and
    // the image will be drawn on the JFrame.
    BufferedImage bufImage;

    WaveEngine waveEngine;

    // This is used to access to the data of bufImage.
    Raster raster;

    // This is again used to access to the data of bufImage. Nothing else to say.
    DataBufferByte dataBuffer;

    // This tells the JFrame's renderer how we want things to be rendered.
    // For example, if we want things to look smoother, we can turn on
    // antialiasing and/or bilinear interpolation.
    RenderingHints renderingHints;

    // This will be the data array of the bufImage. We will modify this directly.
    byte[] data;

    public WaveSimulatorFrame() {

        // Here, we construct the waveEngine. Threads will be created and
        // started but they will be in a sleepy state before we call start().
        waveEngine = new WaveEngine();

        // We are going set up an example scene.
        // Set the first oscillator as a line source.
        waveEngine.setOscillatorSource(0, OscillatorSource.LineSource);

        // Set the first oscillator's primary location relative to the pool area.
        waveEngine.setOscillatorLocation(0, 0, new Point(260, 280));

        // Set the first oscillator's secondary location.
        waveEngine.setOscillatorLocation(0, 1, new Point(280, 240));

        // Emission from a set of particles determined by the line points
        // will be quite powerful compared to that of a point source in which
        // only one particle emits waves so decrease the amplitude.
        waveEngine.setOscillatorAmplitude(0, 0.1);

        // Decrease the period of the first oscillator to make it emit
        // more directional waves like a beam. As we might know, period
        // is inverse of frequency.
        // Note that the wavelength should be above least a few particles thick
        // otherwise unusual propogations may take place.
        waveEngine.setOscillatorPeriod(0, 8);
        
        // Watching high frequency waves may be disturbing and may
        // especially be dangerous for people who have epilepsy.
        // Let's slow down the simulation. The method below sets the number
        // of calculations/iterations per second.
        waveEngine.setIterationsPerSecond(70);
        // We could also increase the mass of the pool if the point is only
        // to slow things down then the first method is more CPU friendly.
        
        // Let's also reduce the FPS to analyze the each frame easier.
        waveEngine.setFramesPerSecond(5);
        
        waveEngine.setAmplitudeMultiplier(10);

        // Enable the oscillator so it will start to emit waves.
        waveEngine.setOscillatorEnabled(0, true);

        // Let's put a mirror to the left.
        
        // We are going to modify particle data which requires a call to lock().
        // Between calls to lock() and unlock(), only getData() must be called,
        // otherwise the thread which called another method will hang.
        // Because of the reasongs mentioned above we call getSize() before
        // calling lock().
        int size = waveEngine.getSize();
        waveEngine.lock();

        // Get the fixity data which determines if a particle is static or
        // dynamic. There are size*size elements in the returned array which
        // is the number of particles in the pool.
        // The method returns an array whose type is determined by the
        // ParticleAttribute. If it is Fixity, the type will be byte[],
        // otherwise the type will be double[].
        byte fixities[] = (byte[]) waveEngine.getData(ParticleAttribute.Fixity);

        // Set a rectangle of static particles to the left.
        for (int x = 10; x < 30; x++) {
            for (int y = 120; y < 220; y++) {
                fixities[x + size * y] = 1;
            }
        }
        
        // Let's also add a different medium to the top-right.
        // Sadly, the different medium will be visible by only its effects on
        // the incident waves (no colors involved).
        double masses[] = (double[]) waveEngine.getData(ParticleAttribute.Mass);
        
        // Set a rectangle of heavier particles to the top.
        for (int x = 180; x < size; x++) {
            for (int y = 0; y < 150; y++) {
               masses[x + size * y] = 0.4;
            }
        }
        
        // After calling unlock(), do not use read/write the data obtained
        // by getData() call. Doing so will either result in a program error or
        // calculation errors.
        waveEngine.unlock();

        // WaveEngine may be set to call a method every time it makes a
        // depiction of the pool. In such a method, we may copy the data
        // that is passed from the WaveEngine to the image data, and signal
        // the window to perform redrawing.
        waveEngine.setRenderEventListener((byte[] bitmap_data) -> {
            // Tips about BGR and RGB formats
            // The image may use BGR instead of RGB. In such as case,
            // the colors will look different because the engine always uses
            // the RGB format. To get around this issue, we may do something
            // similar to the following.
            // For example, we want to set the crest color to red. If the image
            // data format is BGR than swap the red and blue components of the
            // color you want to set to. Set the color to blue instead so crests
            // will look red.
            System.arraycopy(bitmap_data, 0, data, 0, data.length);
            repaint();
        });

        // Construct the bufImage with the default size of the WaveEngine.
        // The pool in the WaveEngine is always a square.
        bufImage = new BufferedImage(size, size, BufferedImage.TYPE_3BYTE_BGR);

        // We reach to the data buffer of the bufImage.
        raster = bufImage.getRaster();
        dataBuffer = (DataBufferByte) raster.getDataBuffer();
        data = dataBuffer.getData();

        // Set a rendering hint such that the image will look smooth.
        renderingHints = new RenderingHints(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Now we can start the waveEngine. The threads will start making
        // calculations as soon as we start the engine. If we stop it,
        // the threads will not return but wait in a sleepy state.
        waveEngine.start();
    }

    @Override
    public void windowClosing(WindowEvent e) {
        // This method doesn't exist but included in the C++ version of the 
        // engine. If you call this, the engine will put itself in a disposed
        // state, and the threads will safely terminate.
        // Don't use the WaveEngine after calling this function.
        // It may not be actually necessary to call this function at all.
        waveEngine.dispose();
    }

    @Override
    public void paint(Graphics g) {
        // Set bilinear interpolation and draw a stretched image.
        Graphics2D g2 = (Graphics2D) g;
        
        int width = rootPane.getWidth();
        int height = rootPane.getHeight();
        int x = rootPane.getX();
        int y = rootPane.getY();
        g2.clearRect(x, y, width, height);
        
        g2.setRenderingHints(renderingHints);
        
        int minimum_size = Math.min(width, height);
        int offsetx = 0;
        int offsety = 0;
        if (width > minimum_size)
            offsetx = (width - minimum_size) / 2;
        else
            offsety = (height - minimum_size) / 2;
        
        g2.drawImage(bufImage, x + offsetx, y + offsety,
                minimum_size, minimum_size, rootPane);

    }

    public static void main(String[] args) {
        WaveSimulatorFrame frame;
        frame = new WaveSimulatorFrame();
        frame.setDefaultCloseOperation(EXIT_ON_CLOSE);
        // Listen for the close event to dispose the waveEngine.
        frame.addWindowListener(frame);
        frame.setLocationByPlatform(true);
        frame.setSize(500, 500);
        frame.setVisible(true);
    }

    // Unused WindowListener methods
    @Override
    public void windowOpened(WindowEvent e) {
    }

    @Override
    public void windowClosed(WindowEvent e) {
    }

    @Override
    public void windowIconified(WindowEvent e) {
    }

    @Override
    public void windowDeiconified(WindowEvent e) {
    }

    @Override
    public void windowActivated(WindowEvent e) {
    }

    @Override
    public void windowDeactivated(WindowEvent e) {
    }

}
