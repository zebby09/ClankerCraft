package clanker.craft.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import clanker.craft.ClankerCraft;

public record TTSSpeakS2CPayload(String text, int entityId) implements CustomPayload {
    public static final Id<TTSSpeakS2CPayload> ID = new Id<>(Identifier.of(ClankerCraft.MOD_ID, "tts_speak"));
    public static final PacketCodec<RegistryByteBuf, TTSSpeakS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, TTSSpeakS2CPayload::text,
            PacketCodecs.VAR_INT, TTSSpeakS2CPayload::entityId,
            TTSSpeakS2CPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
