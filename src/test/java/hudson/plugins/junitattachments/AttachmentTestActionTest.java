package hudson.plugins.junitattachments;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AttachmentTestActionTest {

    @Test
    void testSupportedImageFormats() {
        assertTrue(AttachmentTestAction.isImageFile("foo.jpg"), "JPG should open in a popup");
        assertTrue(AttachmentTestAction.isImageFile("foo.JPEG"), "JPEG should open in a popup");
        assertTrue(AttachmentTestAction.isImageFile("foo.png"), "PNG should open in a popup");
        assertTrue(AttachmentTestAction.isImageFile("foo.svg"), "SVG should open in a popup");
        assertTrue(AttachmentTestAction.isImageFile("foo.gif"), "GIF should open in a popup");
    }

    @Test
    void testUnsupportedImageFormats() {
        assertFalse(AttachmentTestAction.isImageFile("foo.csv"), "CSV should not open in a popup");
    }
}
