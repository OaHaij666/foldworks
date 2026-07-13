package com.foldworks.network;

import com.foldworks.client.ClientTransferGraphCache;
import com.foldworks.transfer.TransferGraphValidator;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record TransferGraphValidationPacket(List<IssueData> issues) implements CustomPacketPayload {
    private static final int MAX_ISSUES = 2048;
    public static final Type<TransferGraphValidationPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("foldworks", "transfer_graph_validation"));

    public record IssueData(String severity, String nodeId, String edgeId, String message) {}

    public static final StreamCodec<ByteBuf, TransferGraphValidationPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public TransferGraphValidationPacket decode(ByteBuf buf) {
            int count = NetworkDecodeLimits.checkedCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_ISSUES, "issues");
            List<IssueData> issues = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                issues.add(new IssueData(
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf)
                ));
            }
            return new TransferGraphValidationPacket(issues);
        }

        @Override
        public void encode(ByteBuf buf, TransferGraphValidationPacket pkt) {
            ByteBufCodecs.VAR_INT.encode(buf, pkt.issues.size());
            for (IssueData issue : pkt.issues) {
                ByteBufCodecs.STRING_UTF8.encode(buf, issue.severity);
                ByteBufCodecs.STRING_UTF8.encode(buf, issue.nodeId);
                ByteBufCodecs.STRING_UTF8.encode(buf, issue.edgeId);
                ByteBufCodecs.STRING_UTF8.encode(buf, issue.message);
            }
        }
    };

    public static TransferGraphValidationPacket from(List<TransferGraphValidator.Issue> issues) {
        List<IssueData> data = new ArrayList<>();
        for (TransferGraphValidator.Issue issue : issues) {
            data.add(new IssueData(issue.severity().name(), issue.nodeId(), issue.edgeId(), issue.message()));
        }
        return new TransferGraphValidationPacket(data);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(TransferGraphValidationPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientTransferGraphCache.updateValidation(packet.issues());
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof com.foldworks.client.screen.TransferGraphScreen screen) screen.onValidationUpdated();
        });
    }
}
