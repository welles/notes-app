namespace Notes.Core.Sync;

public sealed class SyncHandlerRegistry
{
    private readonly Dictionary<string, ISyncHandler> _byType;

    public SyncHandlerRegistry(IEnumerable<ISyncHandler> handlers)
    {
        All = handlers.OrderBy(h => h.ApplyOrder).ToList();
        _byType = All.ToDictionary(h => h.EntityType, StringComparer.Ordinal);
        ByClrType = All.ToDictionary(h => h.ClrType);
    }

    public IReadOnlyList<ISyncHandler> All { get; }
    public IReadOnlyDictionary<Type, ISyncHandler> ByClrType { get; }

    public ISyncHandler? Find(string entityType) => _byType.GetValueOrDefault(entityType);

    public ISyncHandler Get(string entityType) =>
        Find(entityType) ?? throw new InvalidOperationException($"Unknown entity type '{entityType}'.");

    public IEnumerable<string> EntityTypes => All.Select(h => h.EntityType);
}
