import { create, fromBinary, toBinary } from "@bufbuild/protobuf";
import { describe, expect, it } from "vitest";
import { EnvelopeSchema } from "./gen/bastion/v1/envelope_pb.js";
import { MessageBodySchema } from "./gen/bastion/v1/messages_pb.js";

describe("generated protocol types", () => {
  it("round-trips an envelope", () => {
    const envelope = create(EnvelopeSchema, {
      version: 1,
      senderId: new Uint8Array(16).fill(1),
      recipientId: new Uint8Array(16).fill(2),
      keyEpoch: 3,
      nonce: new Uint8Array(24).fill(4),
      ciphertext: new Uint8Array(64).fill(5),
    });
    const decoded = fromBinary(EnvelopeSchema, toBinary(EnvelopeSchema, envelope));
    expect(decoded.keyEpoch).toBe(3);
    expect(decoded.recipientId).toEqual(envelope.recipientId);
  });

  it("round-trips a ring command", () => {
    const body = create(MessageBodySchema, {
      protocolVersion: 1,
      counter: 42n,
      payload: {
        case: "command",
        value: { kind: { case: "ring", value: { durationSeconds: 30, flashlight: true } } },
      },
    });
    const decoded = fromBinary(MessageBodySchema, toBinary(MessageBodySchema, body));
    expect(decoded.counter).toBe(42n);
    expect(decoded.payload.case).toBe("command");
  });
});
