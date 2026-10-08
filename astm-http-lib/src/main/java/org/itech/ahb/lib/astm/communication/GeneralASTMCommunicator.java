package org.itech.ahb.lib.astm.communication;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.lib.astm.concept.ASTMFrame;
import org.itech.ahb.lib.astm.concept.ASTMFrame.FrameType;
import org.itech.ahb.lib.astm.concept.ASTMMessage;
import org.itech.ahb.lib.astm.concept.ASTMRecord;
import org.itech.ahb.lib.astm.concept.DefaultASTMFrame;
import org.itech.ahb.lib.astm.concept.DefaultASTMRecord;
import org.itech.ahb.lib.astm.exception.ASTMCommunicationException;
import org.itech.ahb.lib.astm.exception.FrameParsingException;
import org.itech.ahb.lib.astm.interpretation.ASTMInterpreterFactory;
import org.itech.ahb.lib.astm.servlet.ASTMServlet.ASTMVersion;
import org.itech.ahb.lib.util.AstmCharsets;
import org.itech.ahb.lib.util.LogUtil;
import org.itech.ahb.lib.util.ThreadUtil;

//If this class gets too complicated, separate out the
// LISA-01 and E1382-95 protocols into separate classes or separate sending from receiving.
// Also separate out the non-compliant transmission protocol into a separate class.
/**
 *  This class is a general communicator that can send and receive ASTM messages
 * over protocols such as LIS01-A and E1382-95, as well as non-compliant transmissions
 * where the sender sends ASTM messages by sending the message character by character
 * without using control characters, frame numbers, or checksums.
 */
@Slf4j
public class GeneralASTMCommunicator implements Communicator {

  /**
   *  Errors that can occur when receiving a frame.
   */
  public enum FrameError {
    /**
     * The frame number is expected to be between 0-7 and increase by 1 each frame mod 8
     */
    WRONG_FRAME_NUMBER,
    /**
     * Frame exceeds the maximum frame size
     */
    MAX_SIZE_EXCEEDED,
    /**
     * Received a character in the the Data Content of Message that is not allowed
     */
    ILLEGAL_CHAR,
    /**
     * Checksum does not match the calculated checksum
     */
    BAD_CHECKSUM,
    /**
     * Start caracter is not the expected start character
     */
    ILLEGAL_START,
    /**
     * End character is not the expected end character
     */
    ILLEGAL_END
  }

  private static final char CR = 0x0D;
  private static final char LF = 0x0A;
  private static final char SOH = 0x01;
  private static final char STX = 0x02;
  private static final char ETX = 0x03;
  private static final char EOT = 0x04;
  private static final char ENQ = 0x05;
  private static final char ACK = 0x06;
  private static final char DLE = 0x10;
  private static final char DC1 = 0x11;
  private static final char DC2 = 0x12;
  private static final char DC3 = 0x13;
  private static final char DC4 = 0x14;
  private static final char NAK = 0x15;
  private static final char SYN = 0x16;
  private static final char ETB = 0x17;

  private static final List<Character> RESTRICTED_CHARACTERS = Arrays.asList(
    SOH,
    STX,
    ETX,
    EOT,
    ENQ,
    ACK,
    DLE,
    NAK,
    SYN,
    ETB,
    LF,
    DC1,
    DC2,
    DC3,
    DC4
  );
  private static final char NON_COMPLIANT_START_CHARACTER = 'H';
  private static final String TERMINATION_RECORD_END = "L|1|N";
  private static final int NON_COMPLIANT_RECEIVE_TIMEOUT = 60; // in seconds

  public static final int OVERHEAD_CHARACTER_COUNT = 7;
  public static final int MAX_FRAME_SIZE = 64000;
  public static final int MAX_TEXT_SIZE = MAX_FRAME_SIZE - OVERHEAD_CHARACTER_COUNT;
  private static final int ESTABLISHMENT_SOCKET_TIMEOUT = 60; // in seconds
  private static final int ESTABLISHMENT_SEND_TIMEOUT = 15; // in seconds
  private static final int RECIEVE_FRAME_TIMEOUT = 30; // in seconds
  private static final int SEND_FRAME_TIMEOUT = 15; // in seconds
  private static final int MAX_FRAME_RETRY_ATTEMPTS = 5; // 6 - 1 as retries are after first attmpt
  public static final int MAX_FRAMES_PER_MESSAGE = 4096;
  public static final int MAX_MESSAGE_CHARACTERS = 16 * 1024 * 1024;

  public static final int MAX_FRAME_SIZE_E138195 = 247;
  public static final int MAX_TEXT_SIZE_E138195 = MAX_FRAME_SIZE_E138195 - OVERHEAD_CHARACTER_COUNT;

  // wrapping counter
  private static final AtomicInteger COMMUNICATOR_ID_COUNTER = new AtomicInteger(0);
  private static final int MAX_COMMUNICATOR_ID_COUNTER = 1024;

  /**
   * Gets a new ID for this communicator instance after incrementing the counter
   * @return the new ID
   */
  private final int incrementAndGetId() {
    return COMMUNICATOR_ID_COUNTER.accumulateAndGet(
      1,
      (index, inc) -> (++index > MAX_COMMUNICATOR_ID_COUNTER ? 0 : index)
    );
  }

  private final ASTMInterpreterFactory astmInterpreterFactory;
  private final String communicatorId; // only used for debug messages

  private final Socket socket;
  private final BufferedReader reader;
  private final PrintWriter writer;
  private ASTMVersion astmVersion;
  private Boolean receiveEstablished = false;
  private AstmReceiptObserver receiptObserver = AstmReceiptObserver.NONE;
  private byte[] previousReceivedFrame;
  private Duration frameDeadline = Duration.ofSeconds(RECIEVE_FRAME_TIMEOUT);
  private Duration nonCompliantMessageDeadline = Duration.ofSeconds(NON_COMPLIANT_RECEIVE_TIMEOUT);

  public void setReceiptObserver(AstmReceiptObserver observer) {
    receiptObserver = java.util.Objects.requireNonNull(observer);
  }

  /**
   * Constructor for a GeneralASTMCommunicator, will assume the ASTM version is LIS01-A
   * @param astmInterpreterFactory a factory that will create an interpreter for a received message
   * @param socket the socket to communicate on
   */
  public GeneralASTMCommunicator(ASTMInterpreterFactory astmInterpreterFactory, Socket socket) throws IOException {
    this(astmInterpreterFactory, socket, ASTMVersion.LIS01_A);
  }

  /**
   * Constructor for a GeneralASTMCommunicator with a specific ASTM version
   * @param astmInterpreterFactory a factory that will create an interpreter for a received message
   * @param socket the socket to communicate on
   * @param astmVersion the ASTM version to communicate over
   */
  public GeneralASTMCommunicator(ASTMInterpreterFactory astmInterpreterFactory, Socket socket, ASTMVersion astmVersion)
    throws IOException {
    communicatorId = Integer.toString(incrementAndGetId());
    this.socket = socket;
    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), AstmCharsets.TRANSPORT));
    PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, AstmCharsets.TRANSPORT);

    this.astmInterpreterFactory = astmInterpreterFactory;
    this.reader = reader;
    this.writer = writer;
    this.astmVersion = astmVersion;
  }

  @Override
  public String getID() {
    return communicatorId;
  }

  @Override
  public boolean didReceiveEstablishmentSucceed() {
    return receiveEstablished;
  }

  @Override
  public ASTMMessage receiveProtocol(boolean lineWasContentious)
    throws FrameParsingException, ASTMCommunicationException, IOException, InterruptedException {
    log.trace("starting receive protocol for ASTM message");
    if (astmVersion == ASTMVersion.LIS01_A || astmVersion == ASTMVersion.E1381_95) {
      try {
        receiveEstablished = establishmentReceive();
      } catch (SocketTimeoutException e) {
        log.warn(
          "waited " +
          ESTABLISHMENT_SOCKET_TIMEOUT +
          " " +
          TimeUnit.SECONDS +
          " for the sender to send anything but nothing was received"
        );
        //TODO should we assume that the sender wants to receive data if it doesn't even send a single character?
        throw e;
      }
      if (!receiveEstablished) {
        throw new ASTMCommunicationException(
          "something went wrong in the establishment phase of the receive protocol, possibly the wrong start character was received"
        );
      }
      switch (astmVersion) {
        case E1381_95:
        case LIS01_A:
          try {
            return receiveInCompliantMode();
          } finally {
            receiptObserver.interrupted();
          }
        case NON_COMPLIANT:
        default:
          return receiveInNonCompliantMode();
      }
    }
    log.trace("astm transmission protocol not being used");
    return receiveInNonCompliantMode();
  }

  /** Overrides the receive deadlines; the defaults are the protocol timeouts. */
  void setReceiveDeadlines(Duration frame, Duration nonCompliantMessage) {
    this.frameDeadline = frame;
    this.nonCompliantMessageDeadline = nonCompliantMessage;
  }

  /**
   * Reads one character, failing once the deadline has passed. The socket timeout is the time left,
   * so a silent peer and a trickling one are both cut off.
   */
  private char readCharBefore(long deadlineNanos, String what) throws IOException, InterruptedException {
    long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    if (remaining <= 0) {
      throw new SocketTimeoutException(what + " was not complete within its deadline");
    }
    socket.setSoTimeout((int) Math.min(remaining, Integer.MAX_VALUE));
    return ThreadUtil.readCharWithInterruptCheck(reader);
  }


  public Boolean establishmentReceive() throws IOException, InterruptedException {
    socket.setSoTimeout(ESTABLISHMENT_SOCKET_TIMEOUT * 1000);
    char establishmentChar = (ThreadUtil.readCharWithInterruptCheck(reader));
    log.trace(
      "received: '" +
      LogUtil.convertForDisplay(establishmentChar) +
      "'. Expecting establishment signal [" +
      LogUtil.convertForDisplay(ENQ) +
      "] aka [0x05]"
    );

    if (establishmentChar == ENQ) {
      log.trace("sending: '" + LogUtil.convertForDisplay(ACK) + "' to indicate ready to receive frames");
      writer.append(ACK);
      writer.flush();
      log.trace("astm LIS01-A receive protocol: established");
      return true;
    } else if (establishmentChar == NON_COMPLIANT_START_CHARACTER) {
      //technically the ASTM specs say to "ignore other characters" but we are assuming this is just a non-compliant transmission
      log.debug(
        "protocol assumed to be non-compliant as '" +
        LogUtil.convertForDisplay(establishmentChar) +
        "' was sent. Attempting to read message in non-compliant mode'"
      );
      astmVersion = ASTMVersion.NON_COMPLIANT;
      return true;
    } else {
      //technically the ASTM specs say to "ignore other characters" but we are just stopping communication if somehting else is received
      log.trace(
        "sending: '" +
        LogUtil.convertForDisplay(NAK) +
        "' to indicate not ready to receive frames. Incorrect establishment signal"
      );
      writer.append(NAK);
      writer.flush();
      return false;
    }
  }

    /**
   * Receives an ASTM message that is being sent non-compliantly (not using a proper ASTM transmission protocol).
   * Records are read up to each carriage return until the termination record arrives, within one
   * deadline for the whole message and within the frame and message size limits. A closed or silent
   * connection ends the receipt.
   *
   * @return the received ASTM message.
   * @throws ASTMCommunicationException if the message passes a size limit or the receipt is interrupted.
   * @throws IOException if an I/O error occurs, including the peer closing the connection or the deadline passing.
   */
  private ASTMMessage receiveInNonCompliantMode() throws IOException, ASTMCommunicationException {
    long deadline = System.nanoTime() + nonCompliantMessageDeadline.toNanos();
    List<ASTMRecord> records = new ArrayList<>();
    long characters = 0;
    try {
      while (true) {
        if (records.size() >= MAX_FRAMES_PER_MESSAGE) {
          throw new ASTMCommunicationException("non-compliant message exceeds " + MAX_FRAMES_PER_MESSAGE + " records");
        }
        String text = readNextIncompliantRecord(records, deadline);
        characters += text.length();
        if (characters > MAX_MESSAGE_CHARACTERS) {
          throw new ASTMCommunicationException(
            "non-compliant message exceeds " + MAX_MESSAGE_CHARACTERS + " characters"
          );
        }
        if (!records.isEmpty() && records.get(records.size() - 1).getRecord().trim().endsWith(TERMINATION_RECORD_END)) {
          break;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ASTMCommunicationException("receipt of a non-compliant message was interrupted", e);
    }
    return decodePayload(
      astmInterpreterFactory.createInterpreterForRecords(records).interpretASTMRecordsToMessage(records)
    );
  }

  

  /**
   * Re-reads an assembled message's text as payload text.
   *
   * <p>Frames are read byte-transparently through {@link AstmCharsets#TRANSPORT} so that checksums
   * can be verified against the sender's own arithmetic. That leaves each byte standing for itself,
   * which is already the correct reading for a Latin-1 analyzer but not for one sending UTF-8, where
   * a multi-byte sequence would otherwise surface as mojibake. The encoding is decided once for the
   * whole message and applied to every record, so records cannot disagree with each other.
   *
   * @param message the assembled message, with record text as read off the wire.
   * @return the message with its records re-read as payload text, or the original message if the
   *     transport reading is already correct.
   */
  private ASTMMessage decodePayload(ASTMMessage message) {
    List<ASTMRecord> records = message == null ? null : message.getRecords();
    if (records == null || records.isEmpty()) {
      return message;
    }
    if (!AstmCharsets.isUtf8Payload(message.getMessage())) {
      return message;
    }
    List<ASTMRecord> decodedRecords = new ArrayList<>(records.size());
    for (ASTMRecord record : records) {
      decodedRecords.add(new DefaultASTMRecord(AstmCharsets.decodePayload(record.getRecord())));
    }
    return astmInterpreterFactory
      .createInterpreterForRecords(decodedRecords)
      .interpretASTMRecordsToMessage(decodedRecords);
  }

  /**
   * Receives an ASTM message that is being sent compliantly over the ASTM transmission protocol.
   * This version supports LISA-01 and E1382-95 protocols
   *
   * @return the received ASTM message.
   * @throws FrameParsingException if there is an error parsing the frame.
   * @throws ASTMCommunicationException if there is a communication error in the ASTM transmission protocol.
   * @throws IOException if an I/O error occurs.
   */
  private ASTMMessage receiveInCompliantMode() throws IOException, ASTMCommunicationException, FrameParsingException {
    List<ASTMFrame> frames = new ArrayList<>();
    int i = 0;
    long characters = 0;
    List<Exception> exceptions = new ArrayList<>();
    while (exceptions.size() <= MAX_FRAME_RETRY_ATTEMPTS) {
      if (exceptions.size() > 0) {
        log.debug("attempting retry of frame " + i);
      }
      try {
        ReadFrameInfo frameInfo = receiveNextFrame(frames, System.nanoTime() + frameDeadline.toNanos());
        if (frameInfo.getStartChar() == EOT) {
          break;
        }
        Set<FrameError> frameErrors = frameInfo.getFrameErrors();
        if (frameErrors.isEmpty()) {
          if (frames.size() > MAX_FRAMES_PER_MESSAGE) {
            throw new ASTMCommunicationException("message exceeds " + MAX_FRAMES_PER_MESSAGE + " frames");
          }
          characters += frames.get(frames.size() - 1).getText().length();
          if (characters > MAX_MESSAGE_CHARACTERS) {
            throw new ASTMCommunicationException("message exceeds " + MAX_MESSAGE_CHARACTERS + " characters");
          }
          log.debug("frame successfully received");
          log.trace("sending: '" + LogUtil.convertForDisplay(ACK) + "' to indicate received frame correctly");
          writer.append(ACK); //it is also permitted to send an EOT to try to end the transmission after reading a frame
          writer.flush();
          exceptions = new ArrayList<>(); // reset as retry mechanism is per frame
          ++i;
        } else {
          log.debug("frame unsuccessfully received due to: " + frameErrors);
          log.trace("sending: '" + LogUtil.convertForDisplay(NAK) + "' to indicate received frame incorrectly");
          writer.append(NAK);
          writer.flush();
          exceptions.add(new ASTMCommunicationException("frame unsuccessfully received due to: " + frameErrors));
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ASTMCommunicationException("receipt of a frame was interrupted", e);
      }
    }

    if (exceptions.size() > MAX_FRAME_RETRY_ATTEMPTS) {
      log.error("MAX_FRAME_RETRY_ATTEMPTS reached for frame");
      for (Exception e : exceptions) {
        log.error("" + e.getMessage());
      }
      //sender is supposed to enter the termination phase when max attempts are reached, which means EOT is expected, but is irrelevant)
      try {
        @SuppressWarnings("unused")
        char eotChar = (char) reader.read();
      } catch (SocketTimeoutException e) {
        log.error("socket timed out waiting for end of transmission after max retries reached");
        throw new ASTMCommunicationException(
          "the receiving phase failed or had exceptions exceeding the number of retries",
          e
        );
      }
      throw new ASTMCommunicationException(
        "the receiving phase failed or had exceptions exceeding the number of retries"
      );
    }

    if (frames.isEmpty()) {
      return decodePayload(
        astmInterpreterFactory.createInterpreterForFrames(frames).interpretFramesToASTMMessage(frames)
      );
    }
    if (frames.get(frames.size() - 1).getType() != FrameType.END) {
      throw new ASTMCommunicationException("Incomplete ASTM transmission: EOT before final ETX frame");
    }
    ASTMMessage message = decodePayload(
      astmInterpreterFactory.createInterpreterForFrames(frames).interpretFramesToASTMMessage(frames)
    );
    receiptObserver.complete(message.getMessage());
    return message;
  }

    /**
   * Reads a single frame, or the end of transmission, within the frame deadline and adds a valid
   * frame to the list of frames.
   *
   * @param frames the list of frames that this method will add the next frame to.
   * @param deadline the System.nanoTime() by which the frame must have arrived
   * @return the information about the frame that was read
   * @throws IOException if an I/O error occurs, including the deadline passing.
   * @throws InterruptedException if the operation is interrupted.
   * @throws ASTMCommunicationException if the frame is far larger than the protocol allows.
   */
  private ReadFrameInfo receiveNextFrame(List<ASTMFrame> frames, long deadline)
    throws IOException, InterruptedException, ASTMCommunicationException {
    char startChar = readCharBefore(deadline, "frame");
    log.trace(
      "received: '" +
      LogUtil.convertForDisplay(startChar) +
      "'. Expecting start of frame ['" +
      LogUtil.convertForDisplay(STX) +
      "'] aka [0x02]"
    );
    if (startChar == EOT) {
      log.debug("'" + LogUtil.convertForDisplay(EOT) + "' detected");
      return new ReadFrameInfo(new HashSet<>(), startChar);
    } else if (startChar == STX) {
      return new ReadFrameInfo(readNextCompliantFrame(frames, (frames.size() + 1) % 8, deadline), startChar);
    } else {
      log.error("illegal start character '" + LogUtil.convertForDisplay(startChar) + "' detected");
      return new ReadFrameInfo(Set.of(FrameError.ILLEGAL_START), startChar);
    }
  }

  /**
   * Read the next frame from the reader and add it to the list of frames.
   *
   * @param frames the list of frames that this task will add the next frame to.
   * @param expectedFrameNumber the expected number that the next frame whouls start with.
   * @return a Set of issues with the frame that was received. This will be empty if no issue was detected.
   * @throws IOException if an I/O error occurs.
   * @throws InterruptedException if the operation is interrupted.
   */
  private Set<FrameError> readNextCompliantFrame(List<ASTMFrame> frames, int expectedFrameNumber, long deadline)
    throws IOException, InterruptedException, ASTMCommunicationException {
    log.debug("reading frame...");
    Set<FrameError> frameErrors = new HashSet<>();

    char frameNumberChar = readCharBefore(deadline, "frame");
    log.trace("received: '" + LogUtil.convertForDisplay(frameNumberChar) + "'. Expecting frame number [0-7]");

    int receivedFrameNumber = Character.getNumericValue(frameNumberChar);
    char curChar = readCharBefore(deadline, "frame");

    int frameSize = 0;
    int maxTextSize = (astmVersion == ASTMVersion.LIS01_A ? MAX_TEXT_SIZE : MAX_TEXT_SIZE_E138195);
    boolean sizeExceededLogged = false;
    boolean illegalCharLogged = false;
    StringBuilder textBuilder = new StringBuilder();
    while (curChar != ETB && curChar != ETX) {
      if (RESTRICTED_CHARACTERS.contains(curChar)) {
        frameErrors.add(FrameError.ILLEGAL_CHAR);
        if (!illegalCharLogged) {
          log.error(
            "illegal character detected at position {}: '{}' (0x{}) — subsequent illegal chars suppressed",
            frameSize,
            LogUtil.convertForDisplay(curChar),
            String.format("%02X", (int) curChar)
          );
          illegalCharLogged = true;
        }
      }
      if (maxTextSize < frameSize) {
        if (!sizeExceededLogged) {
          frameErrors.add(FrameError.MAX_SIZE_EXCEEDED);
          log.error(
            "frame size exceeded max {} at position {} — discarding until ETX/ETB (subsequent size errors suppressed)",
            maxTextSize,
            frameSize
          );
          sizeExceededLogged = true;
        }
        // An oversized frame is refused with NAK once it ends; one that keeps going ends the receipt.
        if (frameSize > 2 * maxTextSize) {
          throw new ASTMCommunicationException("frame exceeds " + maxTextSize + " characters without ending");
        }
      } else {
        textBuilder.append(curChar);
      }
      ++frameSize;
      curChar = readCharBefore(deadline, "frame");
    }
    boolean finalFrame = (curChar == ETX);
    String text = textBuilder.toString();
    log.debug("frame text received");
    log.trace(
      "received frame: '" +
      LogUtil.convertForDisplay(text) +
      "'. Expecting ASTM record. Illegal characters [0x00-0x06, 0x08, 0x0A, 0x0E-0x1F, 0x7F, 0xFF]"
    );
    log.trace(
      "received: '" +
      LogUtil.convertForDisplay(curChar) +
      "'. Expecting control code indicating end of text ['" +
      LogUtil.convertForDisplay(ETB) +
      "', '" +
      LogUtil.convertForDisplay(ETX) +
      "'] aka [0x17, 0x03]"
    );
    StringBuilder checksum = new StringBuilder();
    checksum.append(readCharBefore(deadline, "frame"));
    checksum.append(readCharBefore(deadline, "frame"));

    log.debug("checking checksum...");
    if (!checksumFits(checksum.toString(), frameNumberChar, text, curChar)) {
      frameErrors.add(FrameError.BAD_CHECKSUM);
    }
    String endFrameControlCode = "";
    char endOfFrameChar = readCharBefore(deadline, "frame");
    endFrameControlCode = endFrameControlCode + endOfFrameChar;
    if (CR != endOfFrameChar) {
      frameErrors.add(FrameError.ILLEGAL_END);
    }
    endOfFrameChar = readCharBefore(deadline, "frame");
    endFrameControlCode = endFrameControlCode + endOfFrameChar;
    if (LF != endOfFrameChar) {
      frameErrors.add(FrameError.ILLEGAL_END);
    }
    log.trace(
      "received:'" +
      LogUtil.convertForDisplay(endFrameControlCode) +
      "'. Expecting control code indicating end of frame ['" +
      LogUtil.convertForDisplay("" + CR + LF) +
      "'] aka [0x0D0x0A]"
    );

    byte[] exactFrame =
      ("" + STX + frameNumberChar + text + curChar + checksum + endFrameControlCode).getBytes(AstmCharsets.TRANSPORT);
    boolean duplicate =
      receivedFrameNumber == (expectedFrameNumber + 7) % 8 && Arrays.equals(previousReceivedFrame, exactFrame);
    if (receivedFrameNumber != expectedFrameNumber && !duplicate) frameErrors.add(FrameError.WRONG_FRAME_NUMBER);
    if (frameErrors.isEmpty() && duplicate) return frameErrors;
    if (frameErrors.isEmpty()) {
      // This callback must finish its durable commit before this method allows the ACK writer to run.
      receiptObserver.frame(exactFrame);
      previousReceivedFrame = exactFrame;
      ASTMFrame frame = new DefaultASTMFrame();
      frame.setFrameNumber(Character.getNumericValue(frameNumberChar));
      frame.setType(finalFrame ? FrameType.END : FrameType.INTERMEDIATE);
      frame.setText(text);
      frames.add(frame);
      log.debug("frame added to list of frames");
    } else {
      log.debug("frame not added to list of frames due to errors: " + frameErrors);
    }
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    return frameErrors;
  }

    /**
   * Read the next ASTM record and add it to the list of ASTM records unless it carries illegal
   * characters.
   *
   * @param records the list of records that this method will add to
   * @param deadline the System.nanoTime() by which the whole message must have arrived
   * @return the record text as read, whether or not it was added
   * @throws ASTMCommunicationException if the record is longer than a frame may be
   * @throws IOException if an I/O error occurs.
   * @throws InterruptedException if the operation is interrupted.
   */
  private String readNextIncompliantRecord(List<ASTMRecord> records, long deadline)
    throws IOException, InterruptedException, ASTMCommunicationException {
    log.debug("reading incompliant record...");
    boolean illegal = false;
    StringBuilder textBuilder = new StringBuilder();
    char curChar = ' ';
    while (curChar != CR) {
      curChar = readCharBefore(deadline, "non-compliant message");
      if (RESTRICTED_CHARACTERS.contains(curChar)) {
        illegal = true;
      }
      if (textBuilder.length() >= MAX_TEXT_SIZE) {
        throw new ASTMCommunicationException("non-compliant record exceeds " + MAX_TEXT_SIZE + " characters");
      }
      textBuilder.append(curChar);
    }
    String text = textBuilder.toString();
    log.debug("record text received");
    log.trace(
      "received record: '" +
      LogUtil.convertForDisplay(text) +
      "'. Expecting ASTM frame. Illegal characters [0x00-0x06, 0x08, 0x0A, 0x0E-0x1F, 0x7F, 0xFF]"
    );

    if (!illegal) {
      ASTMRecord record = astmInterpreterFactory.createInterpreterForText(text).interpretASTMTextToRecord(text);
      records.add(record);
      log.debug("record added to list of record");
    } else {
      log.debug("record not added: it carries illegal characters");
    }
    return text;
  }

  @Override
  public SendResult sendProtocol(ASTMMessage message)
    throws ASTMCommunicationException, IOException, InterruptedException {
    log.trace("starting sendProtocol for ASTM message");

    List<ASTMFrame> frames = astmInterpreterFactory.createInterpreter(message).interpretASTMMessageToFrames(message);

    Boolean established = false;
    Boolean nakReceived = false;
    final FutureTask<Character> establishedFuture = new FutureTask<>(establishmentTaskSend());
    try {
      establishedFuture.run();
      Character validResponseChar = establishedFuture.get(ESTABLISHMENT_SEND_TIMEOUT, TimeUnit.SECONDS);
      Boolean lineContention = Character.compare(validResponseChar, ENQ) == 0;
      if (lineContention) {
        return new SendResult(true, false);
      }
      established = Character.compare(validResponseChar, ACK) == 0;
      nakReceived = Character.compare(validResponseChar, NAK) == 0;
    } catch (TimeoutException e) {
      establishedFuture.cancel(true);
      log.error("a timeout occured during the establishment phase of the send protocol", e);
    } catch (InterruptedException | ExecutionException e) {
      log.error("the establishment phase of the send protocol was interrupted or had an error in execution", e);
    }

    if (established) {
      log.trace("established");
    } else if (nakReceived) {
      return new SendResult(false, true);
    } else {
      terminationSignal();
      throw new ASTMCommunicationException("received a non-valid response or nothing in the establishment phase");
    }

    List<Exception> exceptions = new ArrayList<>();
    for (int i = 0; i < frames.size(); i++) {
      try {
        sendNextFrameTask(frames.get(i)).call();
      } catch (Exception e) {
        exceptions.add(e);
        log.error("the sending phase was interrupted or had an error in exeuction", e);
      }

      if (exceptions.size() > MAX_FRAME_RETRY_ATTEMPTS) {
        terminationSignal();
        throw new ASTMCommunicationException("the send phase had too many retries sending frame " + i);
      }

      socket.setSoTimeout(SEND_FRAME_TIMEOUT * 1000);
      char response = ' ';
      try {
        response = ThreadUtil.readCharWithInterruptCheck(reader);
      } catch (SocketTimeoutException e) {
        terminationSignal();
        throw new ASTMCommunicationException(
          "timeout occured while waiting for an acknowledgement of the sent frame",
          e
        );
      } catch (InterruptedException e) {
        terminationSignal();
        throw e;
      }
      log.trace(
        "received: '" +
        LogUtil.convertForDisplay(response) +
        "'. Expecting frame acknownledgment [ACK, NAK, EOT] aka [0x06, 0x15, 0x04]"
      );
      if (response == ACK) {
        exceptions = new ArrayList<>();
        continue;
      } else if (response == EOT) {
        terminationSignal();
        throw new ASTMCommunicationException("the send phase was terminated early by the receiver");
      } else if (response == NAK) {
        exceptions.add(new ASTMCommunicationException("NAK received for frame " + i));
        if (exceptions.size() > MAX_FRAME_RETRY_ATTEMPTS) {
          terminationSignal();
          throw new ASTMCommunicationException("the send phase had too many retries sending frame " + i);
        }
        continue;
      } else {
        exceptions.add(new ASTMCommunicationException("Illegal character received in acknowledgment for frame " + i));
        if (exceptions.size() > MAX_FRAME_RETRY_ATTEMPTS) {
          terminationSignal();
          throw new ASTMCommunicationException("the send phase had too many retries sending frame " + i);
        }
        continue;
      }
    }
    terminationSignal();
    return new SendResult(false, false);
  }

  /**
   * Sends the signal to establish communication with the receiver, beginning the "establishment phase" of the ASTM transmission protocol.
   *
   * @return a callable task that returns the response character from the receiver.
   */
  private Callable<Character> establishmentTaskSend() {
    return new Callable<Character>() {
      @Override
      public Character call() throws IOException, InterruptedException {
        socket.setSoTimeout(ESTABLISHMENT_SEND_TIMEOUT * 1000);
        log.trace("sending: '" + LogUtil.convertForDisplay(ENQ) + "' as establishment signal");
        writer.append(ENQ);
        writer.flush();
        char response;
        try {
          response = ThreadUtil.readCharWithInterruptCheck(reader);
        } catch (InterruptedException e) {
          log.error("socket timed out while waiting for response to establishment signal");
          throw e;
        }
        log.trace(
          "received: '" +
          LogUtil.convertForDisplay(response) +
          "'. Expecting establishment response ['" +
          LogUtil.convertForDisplay(ACK) +
          "', '" +
          LogUtil.convertForDisplay(NAK) +
          "', '" +
          LogUtil.convertForDisplay(ENQ) +
          "'] aka [0x06, 0x15, 0x04]"
        );
        if (response == ACK) {
          return ACK;
        } else if (response == NAK) {
          return NAK;
        } else if (response == ENQ) {
          return ENQ;
        } else {
          return null;
        }
      }
    };
  }

  /**
   * Creates a callable task that sends the next ASTM frame to the receiver.
   *
   * @param frame the ASTM frame to send to the reciever.
   * @return a callable task that sends the next ASTM frame and returns true if the frame was sent successfully.
   */
  private Callable<Boolean> sendNextFrameTask(ASTMFrame frame) {
    return new Callable<Boolean>() {
      @Override
      public Boolean call() {
        StringBuilder frameBuilder = new StringBuilder();
        char frameNumber = Character.forDigit(frame.getFrameNumber(), 10);
        char frameTerminator = frame.getType() == FrameType.INTERMEDIATE ? ETB : ETX;
        frameBuilder
          .append(STX) //
          .append(frameNumber) //
          .append(frame.getText()) //
          .append(frameTerminator) //
          .append(checksumCalc(frameNumber, frame.getText(), frameTerminator)) //
          .append(CR) //
          .append(LF);
        String frame = frameBuilder.toString();
        log.trace("sending frame: '" + LogUtil.convertForDisplay(frame) + "'");
        writer.append(frame);
        writer.flush();

        return true;
      }
    };
  }

  /**
   * Sends the termination signal to enter the termination phase of the ASTM transmission protocol.
   */
  private void terminationSignal() {
    log.debug("sending '" + LogUtil.convertForDisplay(EOT) + "' as termination for exchange");
    writer.append(EOT);
    writer.flush();
  }

  /**
   * Sends the termination signal to enter the termination phase of the ASTM transmission protocol.
   *
   * @param checksum the checksum that the calculated checksum should match.
   * @param frameNumber the frame number character to be used in the checksum calculation.
   * @param frame the frame to be used in the checksum calculation.
   * @param frameTerminator the frame terminator character to be used in the checksum calculation.
   * @return true if the checksum matches the calculated checksum.
   */
  private boolean checksumFits(String checksum, char frameNumber, String frame, char frameTerminator) {
    log.trace(
      "received: '" + LogUtil.convertForDisplay(checksum) + "'. Expecting 2 base 16 checksum characters [00-FF]"
    );
    return checksum.equals(checksumCalc(frameNumber, frame, frameTerminator));
  }

  /**
   * Calculates the checksum from the parameters.
   *
   * <p>The sum is taken over the bytes recovered through {@link AstmCharsets#TRANSPORT}, which are
   * byte-for-byte the ones the sender summed, so the result matches whatever encoding the payload
   * uses. Each byte is masked to its unsigned value: Java's {@code byte} is signed, and a frame
   * carrying enough high bytes could otherwise drive the running total negative, which
   * {@code %02X} would then render as eight hex digits instead of two.
   *
   * @param frameNumber the frame number character to be used in the checksum calculation.
   * @param frame the frame to be used in the checksum calculation.
   * @param frameTerminator the frame terminator character to be used in the checksum calculation.
   * @return the calculated checksum as a String.
   */
  private String checksumCalc(char frameNumber, String frame, char frameTerminator) {
    int computedChecksum = 0;
    computedChecksum += frameNumber & 0xFF;
    for (byte curByte : frame.getBytes(AstmCharsets.TRANSPORT)) {
      computedChecksum += curByte & 0xFF;
    }
    computedChecksum += frameTerminator & 0xFF;
    computedChecksum %= 256;
    String checksum = String.format("%02X", computedChecksum);
    log.debug("frame number " + frameNumber + " calculated checksum: " + checksum);
    return checksum;
  }

  /**
   * Object for holding information about the reading a frame.
   */
  @Data
  @AllArgsConstructor
  private class ReadFrameInfo {

    private Set<FrameError> frameErrors;

    private char startChar;
  }
}
