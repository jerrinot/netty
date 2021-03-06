/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
// (BSD License: http://www.opensource.org/licenses/bsd-license)
//
// Copyright (c) 2011, Joe Walnes and contributors
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or
// without modification, are permitted provided that the
// following conditions are met:
//
// * Redistributions of source code must retain the above
// copyright notice, this list of conditions and the
// following disclaimer.
//
// * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the
// following disclaimer in the documentation and/or other
// materials provided with the distribution.
//
// * Neither the name of the Webbit nor the names of
// its contributors may be used to endorse or promote products
// derived from this software without specific prior written
// permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
// CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
// INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
// MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
// CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
// INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
// (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
// GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
// BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
// LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
// OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.

package io.netty.handler.codec.http.websocketx;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.List;

import static io.netty.buffer.ByteBufUtil.readBytes;

/**
 * Decodes a web socket frame from wire protocol version 8 format. This code was forked from <a
 * href="https://github.com/joewalnes/webbit">webbit</a> and modified.
 */
public class WebSocket08FrameDecoder extends ByteToMessageDecoder
        implements WebSocketFrameDecoder {

    enum State {
        READING_FIRST,
        READING_SECOND,
        READING_SIZE,
        MASKING_KEY,
        PAYLOAD,
        CORRUPT
    }

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(WebSocket08FrameDecoder.class);

    private static final byte OPCODE_CONT = 0x0;
    private static final byte OPCODE_TEXT = 0x1;
    private static final byte OPCODE_BINARY = 0x2;
    private static final byte OPCODE_CLOSE = 0x8;
    private static final byte OPCODE_PING = 0x9;
    private static final byte OPCODE_PONG = 0xA;

    private UTF8Output fragmentedFramesText;
    private int fragmentedFramesCount;
    private boolean frameFinalFlag;
    private int frameRsv;
    private int frameOpcode;
    private long framePayloadLength;
    private byte[] maskingKey;
    private int framePayloadLen1;
    private boolean receivedClosingHandshake;

    private final long maxFramePayloadLength;
    private final boolean allowExtensions;
    private final boolean maskedPayload;

    private State state = State.READING_FIRST;

    /**
     * Constructor
     *
     * @param maskedPayload
     *            Web socket servers must set this to true processed incoming masked payload. Client implementations
     *            must set this to false.
     * @param allowExtensions
     *            Flag to allow reserved extension bits to be used or not
     * @param maxFramePayloadLength
     *            Maximum length of a frame's payload. Setting this to an appropriate value for you application
     *            helps check for denial of services attacks.
     */
    public WebSocket08FrameDecoder(boolean maskedPayload, boolean allowExtensions, int maxFramePayloadLength) {
        this.maskedPayload = maskedPayload;
        this.allowExtensions = allowExtensions;
        this.maxFramePayloadLength = maxFramePayloadLength;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {

        // Discard all data received if closing handshake was received before.
        if (receivedClosingHandshake) {
            in.skipBytes(actualReadableBytes());
            return;
        }
            switch (state) {
                case READING_FIRST:
                    framePayloadLength = 0;

                    // FIN, RSV, OPCODE
                    byte b = in.readByte();
                    frameFinalFlag = (b & 0x80) != 0;
                    frameRsv = (b & 0x70) >> 4;
                    frameOpcode = b & 0x0F;

                    if (logger.isDebugEnabled()) {
                        logger.debug("Decoding WebSocket Frame opCode={}", frameOpcode);
                    }

                    state = State.READING_SECOND;
                case READING_SECOND:
                    if (!in.isReadable()) {
                        return;
                    }
                    // MASK, PAYLOAD LEN 1
                    b = in.readByte();
                    boolean frameMasked = (b & 0x80) != 0;
                    framePayloadLen1 = b & 0x7F;

                    if (frameRsv != 0 && !allowExtensions) {
                        protocolViolation(ctx, "RSV != 0 and no extension negotiated, RSV:" + frameRsv);
                        return;
                    }

                    if (maskedPayload && !frameMasked) {
                        protocolViolation(ctx, "unmasked client to server frame");
                        return;
                    }
                    if (frameOpcode > 7) { // control frame (have MSB in opcode set)

                        // control frames MUST NOT be fragmented
                        if (!frameFinalFlag) {
                            protocolViolation(ctx, "fragmented control frame");
                            return;
                        }

                        // control frames MUST have payload 125 octets or less
                        if (framePayloadLen1 > 125) {
                            protocolViolation(ctx, "control frame with payload length > 125 octets");
                            return;
                        }

                        // check for reserved control frame opcodes
                        if (!(frameOpcode == OPCODE_CLOSE || frameOpcode == OPCODE_PING
                                || frameOpcode == OPCODE_PONG)) {
                            protocolViolation(ctx, "control frame using reserved opcode " + frameOpcode);
                            return;
                        }

                        // close frame : if there is a body, the first two bytes of the
                        // body MUST be a 2-byte unsigned integer (in network byte
                        // order) representing a getStatus code
                        if (frameOpcode == 8 && framePayloadLen1 == 1) {
                            protocolViolation(ctx, "received close control frame with payload len 1");
                            return;
                        }
                    } else { // data frame
                        // check for reserved data frame opcodes
                        if (!(frameOpcode == OPCODE_CONT || frameOpcode == OPCODE_TEXT
                                || frameOpcode == OPCODE_BINARY)) {
                            protocolViolation(ctx, "data frame using reserved opcode " + frameOpcode);
                            return;
                        }

                        // check opcode vs message fragmentation state 1/2
                        if (fragmentedFramesCount == 0 && frameOpcode == OPCODE_CONT) {
                            protocolViolation(ctx, "received continuation data frame outside fragmented message");
                            return;
                        }

                        // check opcode vs message fragmentation state 2/2
                        if (fragmentedFramesCount != 0 && frameOpcode != OPCODE_CONT && frameOpcode != OPCODE_PING) {
                            protocolViolation(ctx,
                                    "received non-continuation data frame while inside fragmented message");
                            return;
                        }
                    }

                    state = State.READING_SIZE;
                 case READING_SIZE:

                    // Read frame payload length
                    if (framePayloadLen1 == 126) {
                        if (in.readableBytes() < 2) {
                            return;
                        }
                        framePayloadLength = in.readUnsignedShort();
                        if (framePayloadLength < 126) {
                            protocolViolation(ctx, "invalid data frame length (not using minimal length encoding)");
                            return;
                        }
                    } else if (framePayloadLen1 == 127) {
                        if (in.readableBytes() < 8) {
                            return;
                        }
                        framePayloadLength = in.readLong();
                        // TODO: check if it's bigger than 0x7FFFFFFFFFFFFFFF, Maybe
                        // just check if it's negative?

                        if (framePayloadLength < 65536) {
                            protocolViolation(ctx, "invalid data frame length (not using minimal length encoding)");
                            return;
                        }
                    } else {
                        framePayloadLength = framePayloadLen1;
                    }

                    if (framePayloadLength > maxFramePayloadLength) {
                        protocolViolation(ctx, "Max frame length of " + maxFramePayloadLength + " has been exceeded.");
                        return;
                    }

                    if (logger.isDebugEnabled()) {
                        logger.debug("Decoding WebSocket Frame length={}", framePayloadLength);
                    }

                    state = State.MASKING_KEY;
                case MASKING_KEY:
                    if (maskedPayload) {
                        if (in.readableBytes() < 4) {
                            return;
                        }
                        if (maskingKey == null) {
                            maskingKey = new byte[4];
                        }
                        in.readBytes(maskingKey);
                    }
                    state = State.PAYLOAD;
                case PAYLOAD:
                    if (in.readableBytes() < framePayloadLength) {
                        return;
                    }

                    ByteBuf payloadBuffer = null;
                    try {
                        payloadBuffer = readBytes(ctx.alloc(), in, toFrameLength(framePayloadLength));

                        // Now we have all the data, the next checkpoint must be the next
                        // frame
                        state = State.READING_FIRST;

                        // Unmask data if needed
                        if (maskedPayload) {
                            unmask(payloadBuffer);
                        }

                        // Processing ping/pong/close frames because they cannot be
                        // fragmented
                        if (frameOpcode == OPCODE_PING) {
                            out.add(new PingWebSocketFrame(frameFinalFlag, frameRsv, payloadBuffer));
                            payloadBuffer = null;
                            return;
                        }
                        if (frameOpcode == OPCODE_PONG) {
                            out.add(new PongWebSocketFrame(frameFinalFlag, frameRsv, payloadBuffer));
                            payloadBuffer = null;
                            return;
                        }
                        if (frameOpcode == OPCODE_CLOSE) {
                            checkCloseFrameBody(ctx, payloadBuffer);
                            receivedClosingHandshake = true;
                            out.add(new CloseWebSocketFrame(frameFinalFlag, frameRsv, payloadBuffer));
                            payloadBuffer = null;
                            return;
                        }

                        // Processing for possible fragmented messages for text and binary
                        // frames
                        String aggregatedText = null;
                        if (frameFinalFlag) {
                            // Final frame of the sequence. Apparently ping frames are
                            // allowed in the middle of a fragmented message
                            if (frameOpcode != OPCODE_PING) {
                                fragmentedFramesCount = 0;

                                // Check text for UTF8 correctness
                                if (frameOpcode == OPCODE_TEXT || fragmentedFramesText != null) {
                                    // Check UTF-8 correctness for this payload
                                    checkUTF8String(ctx, payloadBuffer);

                                    // This does a second check to make sure UTF-8
                                    // correctness for entire text message
                                    aggregatedText = fragmentedFramesText.toString();

                                    fragmentedFramesText = null;
                                }
                            }
                        } else {
                            // Not final frame so we can expect more frames in the
                            // fragmented sequence
                            if (fragmentedFramesCount == 0) {
                                // First text or binary frame for a fragmented set
                                fragmentedFramesText = null;
                                if (frameOpcode == OPCODE_TEXT) {
                                    checkUTF8String(ctx, payloadBuffer);
                                }
                            } else {
                                // Subsequent frames - only check if init frame is text
                                if (fragmentedFramesText != null) {
                                    checkUTF8String(ctx, payloadBuffer);
                                }
                            }

                            // Increment counter
                            fragmentedFramesCount++;
                        }

                        // Return the frame
                        if (frameOpcode == OPCODE_TEXT) {
                            out.add(new TextWebSocketFrame(frameFinalFlag, frameRsv, payloadBuffer));
                            payloadBuffer = null;
                            return;
                        } else if (frameOpcode == OPCODE_BINARY) {
                            out.add(new BinaryWebSocketFrame(frameFinalFlag, frameRsv, payloadBuffer));
                            payloadBuffer = null;
                            return;
                        } else if (frameOpcode == OPCODE_CONT) {
                            out.add(new ContinuationWebSocketFrame(frameFinalFlag, frameRsv,
                                    payloadBuffer, aggregatedText));
                            payloadBuffer = null;
                            return;
                        } else {
                            throw new UnsupportedOperationException("Cannot decode web socket frame with opcode: "
                                    + frameOpcode);
                        }
                    } finally {
                        if (payloadBuffer != null) {
                            payloadBuffer.release();
                        }
                    }
                case CORRUPT:
                    if (in.isReadable()) {
                        // If we don't keep reading Netty will throw an exception saying
                        // we can't return null if no bytes read and state not changed.
                        in.readByte();
                    }
                    return;
                default:
                    throw new Error("Shouldn't reach here.");
            }
    }

    private void unmask(ByteBuf frame) {
        for (int i = frame.readerIndex(); i < frame.writerIndex(); i++) {
            frame.setByte(i, frame.getByte(i) ^ maskingKey[i % 4]);
        }
    }

    private void protocolViolation(ChannelHandlerContext ctx, String reason) {
        state = State.CORRUPT;
        if (ctx.channel().isActive()) {
            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
        throw new CorruptedFrameException(reason);
    }

    private static int toFrameLength(long l) {
        if (l > Integer.MAX_VALUE) {
            throw new TooLongFrameException("Length:" + l);
        } else {
            return (int) l;
        }
    }

    private void checkUTF8String(ChannelHandlerContext ctx, ByteBuf buffer) {
        try {
            if (fragmentedFramesText == null) {
                fragmentedFramesText = new UTF8Output(buffer);
            } else {
                fragmentedFramesText.write(buffer);
            }
        } catch (UTF8Exception ex) {
            protocolViolation(ctx, "invalid UTF-8 bytes");
        }
    }

    /** */
    protected void checkCloseFrameBody(
            ChannelHandlerContext ctx, ByteBuf buffer) {
        if (buffer == null || !buffer.isReadable()) {
            return;
        }
        if (buffer.readableBytes() == 1) {
            protocolViolation(ctx, "Invalid close frame body");
        }

        // Save reader index
        int idx = buffer.readerIndex();
        buffer.readerIndex(0);

        // Must have 2 byte integer within the valid range
        int statusCode = buffer.readShort();
        if (statusCode >= 0 && statusCode <= 999 || statusCode >= 1004 && statusCode <= 1006
                || statusCode >= 1012 && statusCode <= 2999) {
            protocolViolation(ctx, "Invalid close frame getStatus code: " + statusCode);
        }

        // May have UTF-8 message
        if (buffer.isReadable()) {
            try {
                new UTF8Output(buffer);
            } catch (UTF8Exception ex) {
                protocolViolation(ctx, "Invalid close frame reason text. Invalid UTF-8 bytes");
            }
        }

        // Restore reader index
        buffer.readerIndex(idx);
    }
}
