package org.itech.ahb.lib.astm.concept;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Data;

@Data
public class DefaultASTMMessage implements ASTMMessage {

  private List<ASTMRecord> records;

  /**
   * Default constructor for creating a blank ASTM message
   */
  public DefaultASTMMessage() {}

  /**
   * Constructor for creating an ASTM message by passing in the message as a string.
   * @param message the message to parse into a series of ASTM records that will make up the message.
   */
  public DefaultASTMMessage(String message) {
    records = Arrays.stream(message.split("\\r?\\n")).map(e -> new DefaultASTMRecord(e)).collect(Collectors.toList());
  }

  /**
   * Constructor for creating an ASTM message by passing in the message as a list of records.
   * @param records the list of records that make up this message.
   */
  public DefaultASTMMessage(List<ASTMRecord> records) {
    this.records = records;
  }

  @Override
  public int getMessageLength() {
    if (records == null) {
      return 0;
    } else {
      return records.stream().mapToInt(record -> record.getRecordLength()).sum();
    }
  }

  @Override
  public String getMessage() {
    if (records == null) {
      return "";
    }
    return records.stream().map(record -> record.getRecord()).collect(Collectors.joining("\r"));
  }

  @Override
  public void addRecord(ASTMRecord record) {
    if (records == null) {
      records = new ArrayList<>();
    }
    records.add(record);
  }

  @Override
  public List<ASTMRecord> getRecords() {
    if (records == null) {
      return null;
    }
    return Collections.unmodifiableList(records);
  }
}
