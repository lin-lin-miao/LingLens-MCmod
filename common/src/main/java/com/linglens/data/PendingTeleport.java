package com.linglens.data;

public class PendingTeleport {
    private final java.util.UUID playerUuid;
    private final String playerName;
    private final double x, y, z;
    private final String dimension;
    private final long timestamp;

    public PendingTeleport(java.util.UUID playerUuid, String playerName, double x, double y, double z, String dimension, long timestamp) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
        this.timestamp = timestamp;
    }

    public java.util.UUID getPlayerUuid() { return playerUuid; }
    public String getPlayerName() { return playerName; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public String getDimension() { return dimension; }
    public long getTimestamp() { return timestamp; }
}