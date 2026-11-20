using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Notes.Core.Sync;

/// <summary>Tolerant, typed reading of fields from entity JSON. Throws <see cref="SyncDataException"/> on wrong types.</summary>
public static class JsonFields
{
    public static bool Has(JsonObject data, string name, out JsonNode? node) => data.TryGetPropertyValue(name, out node);

    public static string? String(JsonNode? node, string name) => node switch
    {
        null => null,
        JsonValue v when v.GetValueKind() == JsonValueKind.String => v.GetValue<string>(),
        _ => throw new SyncDataException($"'{name}' must be a string."),
    };

    public static bool Bool(JsonNode? node, string name) => node switch
    {
        JsonValue v when v.GetValueKind() is JsonValueKind.True or JsonValueKind.False => v.GetValue<bool>(),
        _ => throw new SyncDataException($"'{name}' must be a boolean."),
    };

    public static double Double(JsonNode? node, string name) => node switch
    {
        JsonValue v when v.GetValueKind() == JsonValueKind.Number => double.Parse(v.ToJsonString(), CultureInfo.InvariantCulture),
        _ => throw new SyncDataException($"'{name}' must be a number."),
    };

    public static Guid Guid(JsonNode? node, string name) =>
        String(node, name) is { } s && System.Guid.TryParse(s, out var g) ? g : throw new SyncDataException($"'{name}' must be a UUID.");

    public static DateTime? DateTime(JsonNode? node, string name)
    {
        if (node is null) return null;
        var s = String(node, name);
        if (!System.DateTime.TryParse(s, CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal | DateTimeStyles.AssumeUniversal, out var dt))
            throw new SyncDataException($"'{name}' must be an ISO-8601 timestamp.");
        return SyncTime.Normalize(dt);
    }

    public static JsonNode? Value(DateTime? dt) => dt is null ? null : JsonValue.Create(SyncTime.Format(dt.Value));
}

public static class SyncTime
{
    /// <summary>UTC, truncated to milliseconds – the precision used everywhere in sync (idempotency compares exact values).</summary>
    public static DateTime Normalize(DateTime dt)
    {
        var utc = dt.Kind switch
        {
            DateTimeKind.Utc => dt,
            DateTimeKind.Local => dt.ToUniversalTime(),
            _ => System.DateTime.SpecifyKind(dt, DateTimeKind.Utc),
        };
        return new DateTime(utc.Ticks - utc.Ticks % TimeSpan.TicksPerMillisecond, DateTimeKind.Utc);
    }

    public static DateTime Now(TimeProvider time) => Normalize(time.GetUtcNow().UtcDateTime);

    public static string Format(DateTime dt) => Normalize(dt).ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture);
}
