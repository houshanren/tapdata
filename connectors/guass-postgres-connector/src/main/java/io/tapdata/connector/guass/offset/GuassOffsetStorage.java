package io.tapdata.connector.guass.offset;

import io.tapdata.connector.postgres.cdc.offset.PostgresOffset;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GuassOffsetStorage {
    public static Map<String, GuassOffset> guassOffsetMap = new ConcurrentHashMap<>(); //one slot one key
}