package org.itech.ahb.lib.astm.interpretation;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.lib.astm.communication.GeneralASTMCommunicator;
import org.itech.ahb.lib.astm.concept.ASTMFrame;
import org.itech.ahb.lib.astm.concept.ASTMFrame.FrameType;
import org.itech.ahb.lib.astm.concept.ASTMMessage;
import org.itech.ahb.lib.astm.concept.ASTMRecord;
import org.itech.ahb.lib.astm.concept.DefaultASTMFrame;
import org.itech.ahb.lib.astm.concept.DefaultASTMMessage;
import org.itech.ahb.lib.astm.concept.DefaultASTMRecord;
import org.itech.ahb.lib.astm.exception.FrameParsingException;

/**
 * This class provides a default implementation of the ASTMInterpreter interface for interpreting many of the default object types.
 */
@Slf4j
public class DefaultASTMInterpreter implements ASTMInterpreter {

  private static final String RECORD_SEPERATOR = Character.toString(0x0D); // CR
  private static final String MESSAGE_TERMINATOR_RECORD_START = "L";

  @Override
  public ASTMMessage interpretFramesToASTMMessage(List<ASTMFrame> frames) throws FrameParsingException {
    log.debug("interpreting frames as astm messages...");
    ASTMMessage message = new DefaultASTMMessage();

    StringBuilder recordBuilder = new StringBuilder();

    for (ASTMFrame frame : frames) {
      log.trace("frame: " + frame);
      if (frame.getType() == FrameType.INTERMEDIATE || !frameContainsMessageTerminator(frame)) {
        log.debug("adding frame to ASTM message");
        recordBuilder.append(frame.getText());
      } else if (frame.getType() == FrameType.END) {
        log.debug("adding end frame to ASTM message");
        recordBuilder.append(frame.getText());
        String record = recordBuilder.toString();
        recordBuilder.setLength(0);
        message.addRecord(new DefaultASTMRecord(record));
        log.trace("added record: '" + record + "' to list of records in message");
      } else {
        throw new FrameParsingException("frame type is an unrecognized type so message cannot be reconstructed");
      }
    }
    if (recordBuilder.length() > 0) {
      String record = recordBuilder.toString();
      log.warn(
        "transmission ended without an ETX-terminated final frame; recovering " +
        record.length() +
        " buffered characters as the final record"
      );
      message.addRecord(new DefaultASTMRecord(record));
    }
    log.debug("finished interpreting frames as astm messages");
    return message;
  }

  /**
   * Checks if a frame contains a message terminator.
   *
   * @param frame the ASTM frame.
   * @return true if the frame contains a message terminator, false otherwise.
   */
  private boolean frameContainsMessageTerminator(ASTMFrame frame) {
    log.trace("checking if frame contains message terminator...");
    String[] lines = frame.getText().split(RECORD_SEPERATOR);
    if (lines[lines.length - 1].startsWith(MESSAGE_TERMINATOR_RECORD_START)) {
      log.trace("frame contains message terminator");
      return true;
    }
    log.trace("frame does NOT contain message terminator");
    return false;
  }

  private List<ASTMFrame> interpretASTMRecordsToFrames(ASTMRecord record, int recordNumber) {
    log.debug("interpreting astm record as frames...");
    List<ASTMFrame> frames = new ArrayList<>();
    String[] frameTexts = record.getRecord().split("(?<=\\G.{" + GeneralASTMCommunicator.MAX_TEXT_SIZE + "})");
    log.trace("astm record: " + record);
    for (int i = 0; i < frameTexts.length; i++) {
      ASTMFrame curFrame = new DefaultASTMFrame();
      curFrame.setText(frameTexts[i]);
      curFrame.setType(i != (frameTexts.length - 1) ? FrameType.INTERMEDIATE : FrameType.END);
      frames.add(curFrame);
      log.trace(curFrame.toString());
    }
    log.trace("record was interpreted across " + frames.size() + " frames");
    log.debug("finished interpreting astm record as frames");
    return frames;
  }

  @Override
  public List<ASTMFrame> interpretASTMMessageToFrames(ASTMMessage message) {
    log.debug("interpreting astm messages as frames...");
    List<ASTMFrame> frames = new ArrayList<>();
    log.trace("astm message: " + message.getMessage());
    for (int i = 0; i < message.getRecords().size(); i++) {
      frames.addAll(interpretASTMRecordsToFrames(message.getRecords().get(i), i));
    }
    for (int i = 0; i < frames.size(); i++) {
      frames.get(i).setFrameNumber((i + 1) % 8);
    }

    log.trace("message was interpreted across " + frames.size() + " frames");
    log.debug("finished interpreting astm message as frames");
    return frames;
  }

  @Override
  public ASTMMessage interpretASTMRecordsToMessage(List<ASTMRecord> records) {
    return new DefaultASTMMessage(records);
  }

  @Override
  public ASTMRecord interpretASTMTextToRecord(String recordText) {
    return new DefaultASTMRecord(recordText);
  }

  @Override
  public ASTMMessage interpretASTMTextToMessage(String messageText) {
    return new DefaultASTMMessage(messageText);
  }
}
