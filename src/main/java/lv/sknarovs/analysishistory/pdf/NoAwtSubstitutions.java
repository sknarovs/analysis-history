package lv.sknarovs.analysishistory.pdf;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Native image only: AWT's native libraries are never loaded. PDFBox's {@code PDDocument} static initialiser warms up
 * AWT colour management and already catches the {@link UnsatisfiedLinkError}; text extraction never needs AWT's native
 * code. Without these substitutions a binary on a machine with a JDK on its library path loads that JDK's
 * {@code libawt} and {@code libjvm}.
 */
public final class NoAwtSubstitutions {
    private NoAwtSubstitutions() {
    }

    @TargetClass(className = "java.awt.image.ColorModel")
    static final class Target_java_awt_image_ColorModel {
        @Substitute
        static void loadLibraries() {
            throw new UnsatisfiedLinkError("AWT native libraries are not available in this binary");
        }
    }

    @TargetClass(className = "java.awt.Toolkit")
    static final class Target_java_awt_Toolkit {
        @Substitute
        static void loadLibraries() {
            throw new UnsatisfiedLinkError("AWT native libraries are not available in this binary");
        }
    }
}
