using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace Notes.Core.Sync;

/// <summary>An entity as delivered by pull. <c>Data</c> is the full JSON of the entity.</summary>
public sealed record SyncEnvelope(string EntityType, Guid Id, long Version, bool Deleted, JsonObject Data);

/// <summary>A change sent by a client. <c>BaseVersion</c> is null for new entities. <c>Data</c> is merged (missing fields stay unchanged, explicit null clears).</summary>
public sealed record PushEnvelope(string EntityType, Guid Id, long? BaseVersion, DateTime ModifiedAt, bool Deleted, JsonObject? Data);

[JsonConverter(typeof(JsonStringEnumConverter<PushStatus>))]
public enum PushStatus
{
    /// <summary>Change applied as sent.</summary>
    Accepted,
    /// <summary>Base version did not match; resolved by the server (see <see cref="PushEntryResult.Winner"/>).</summary>
    Conflict,
    /// <summary>Not applied (see <see cref="PushEntryResult.Reason"/>). The client must keep the change.</summary>
    Rejected,
}

[JsonConverter(typeof(JsonStringEnumConverter<ConflictWinner>))]
public enum ConflictWinner { Client, Server }

public static class RejectReasons
{
    public const string UnknownType = "unknown_type";
    public const string UnknownContext = "unknown_context";
    public const string InvalidData = "invalid_data";
}

public static class ConflictReasons
{
    public const string ContextNotEmpty = "context_not_empty";
    public const string DefaultContext = "default_context";
}

public sealed record PushEntryResult(
    string EntityType,
    Guid Id,
    PushStatus Status,
    string? Reason = null,
    ConflictWinner? Winner = null,
    EntityRef? ConflictCopy = null,
    SyncEnvelope? Server = null);

public sealed record PushResponse(IReadOnlyList<PushEntryResult> Results);

public sealed record PullResponse(IReadOnlyList<SyncEnvelope> Changes, long Cursor, bool HasMore, bool FullResyncRequired);

/// <summary>Where a push comes from.</summary>
public enum PushOrigin
{
    /// <summary>A sync client (Android/desktop) via the API: idempotent per (entityType, id, modifiedAt).</summary>
    Client,
    /// <summary>The server itself (web UI, core actions): no idempotency log, modifiedAt kept strictly increasing.</summary>
    Local,
}
