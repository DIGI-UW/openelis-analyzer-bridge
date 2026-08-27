package org.itech.ahb.lib.astm.interpretation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.itech.ahb.lib.astm.concept.ASTMFrame;
import org.itech.ahb.lib.astm.concept.ASTMFrame.FrameType;
import org.itech.ahb.lib.astm.concept.ASTMMessage;
import org.itech.ahb.lib.astm.concept.DefaultASTMFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultASTMInterpreterTest {

  private static final String CR = "\r";

  private final DefaultASTMInterpreter interpreter = new DefaultASTMInterpreter();

  private static ASTMFrame frame(int number, FrameType type, String text) {
    ASTMFrame frame = new DefaultASTMFrame();
    frame.setFrameNumber(number);
    frame.setType(type);
    frame.setText(text);
    return frame;
  }

  @Test
  @DisplayName("an ETX-terminated transmission assembles into one record")
  void assemblesCompleteTransmission() throws Exception {
    List<ASTMFrame> frames = List.of(
      frame(1, FrameType.INTERMEDIATE, "H|@^\\|GXM-28780012200||" + CR + "P|1|||116 RELANCE JL 2026|"),
      frame(2, FrameType.END, CR + "R|1|^^^EV|NON DÉTECTÉ^|copies/mL|A||F" + CR + "L|1|N")
    );

    ASTMMessage message = interpreter.interpretFramesToASTMMessage(frames);

    assertEquals(1, message.getRecords().size());
    assertTrue(message.getMessage().startsWith("H|@^\\|GXM-28780012200||"));
    assertTrue(message.getMessage().contains("NON DÉTECTÉ"));
    assertTrue(message.getMessage().endsWith("L|1|N"));
  }

  @Test
  @DisplayName("buffered frames are recovered when the sender aborts before sending an ETX frame")
  void recoversTransmissionTruncatedBeforeEndFrame() throws Exception {
    List<ASTMFrame> frames = List.of(
      frame(1, FrameType.INTERMEDIATE, "H|@^\\|GXM-28780012200||" + CR + "P|1|||116 RELANCE JL 2026|")
    );

    ASTMMessage message = interpreter.interpretFramesToASTMMessage(frames);

    assertEquals(1, message.getRecords().size(), "the buffered frame text must not be discarded");
    assertEquals("H|@^\\|GXM-28780012200||" + CR + "P|1|||116 RELANCE JL 2026|", message.getMessage());
  }

  @Test
  @DisplayName("a second terminated message does not accumulate the first message's text")
  void doesNotLeakEarlierRecordsIntoLaterOnes() throws Exception {
    List<ASTMFrame> frames = List.of(
      frame(1, FrameType.END, "H|@^\\|FIRST|" + CR + "L|1|N"),
      frame(2, FrameType.END, "H|@^\\|SECOND|" + CR + "L|1|N")
    );

    ASTMMessage message = interpreter.interpretFramesToASTMMessage(frames);

    assertEquals(2, message.getRecords().size());
    assertEquals("H|@^\\|FIRST|" + CR + "L|1|N", message.getRecords().get(0).getRecord());
    assertEquals(
      "H|@^\\|SECOND|" + CR + "L|1|N",
      message.getRecords().get(1).getRecord(),
      "the second record must not carry the first record's text"
    );
  }

  @Test
  @DisplayName("no frames yields an empty message rather than a spurious record")
  void emptyFrameListYieldsNoRecords() throws Exception {
    ASTMMessage message = interpreter.interpretFramesToASTMMessage(List.of());

    assertEquals("", message.getMessage());
  }
}
