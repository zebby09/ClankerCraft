package clanker.craft.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import clanker.craft.ClankerCraft;

public record TtsSpeakS2CPayload(String text) implements CustomPayload {
    public static final Id<TtsSpeakS2CPayload> ID = new Id<>(Identifier.of(ClankerCraft.MOD_ID, "tts_speak"));
    public static final PacketCodec<RegistryByteBuf, TtsSpeakS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, TtsSpeakS2CPayload::text,
            TtsSpeakS2CPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

