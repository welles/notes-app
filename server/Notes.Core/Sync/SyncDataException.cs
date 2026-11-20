namespace Notes.Core.Sync;

/// <summary>Thrown when the <c>data</c> of a push envelope cannot be applied (wrong types, missing required fields).</summary>
public sealed class SyncDataException(string message) : Exception(message);

/// <summary>Thrown by core actions when a push they depend on was not accepted.</summary>
public sealed class SyncRejectedException(IReadOnlyList<PushEntryResult> results)
    : Exception("Change was not accepted: " + string.Join(", ", results.Where(r => r.Status != PushStatus.Accepted).Select(r => $"{r.EntityType}/{r.Id}: {r.Status} {r.Reason}")))
{
    public IReadOnlyList<PushEntryResult> Results { get; } = results;
}
