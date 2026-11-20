using Notes.Core.Data;

namespace Notes.Core.Events;

/// <summary>
/// In-process event bus. Published after a change has been committed, regardless of whether it came from the web UI or a client push.
/// Used to live-update open web views.
/// </summary>
public sealed class EventBus
{
    private readonly Lock _lock = new();
    private ImmutableSubscribers _subscribers = new([]);

    public IDisposable Subscribe(Action<IReadOnlyList<EntityChange>> handler)
    {
        lock (_lock) _subscribers = new([.. _subscribers.Items, handler]);
        return new Subscription(this, handler);
    }

    /// <summary>Subscribes to changes of the given entity types only.</summary>
    public IDisposable Subscribe(IReadOnlySet<string> entityTypes, Action<IReadOnlyList<EntityChange>> handler) =>
        Subscribe(changes =>
        {
            var relevant = changes.Where(c => entityTypes.Contains(c.Ref.EntityType)).ToList();
            if (relevant.Count > 0) handler(relevant);
        });

    public void Publish(IReadOnlyList<EntityChange> changes)
    {
        if (changes.Count == 0) return;
        foreach (var s in _subscribers.Items)
        {
            try { s(changes); }
            catch { /* a broken subscriber must not break writes */ }
        }
    }

    private void Unsubscribe(Action<IReadOnlyList<EntityChange>> handler)
    {
        lock (_lock) _subscribers = new([.. _subscribers.Items.Where(h => h != handler)]);
    }

    private sealed record ImmutableSubscribers(Action<IReadOnlyList<EntityChange>>[] Items);

    private sealed class Subscription(EventBus bus, Action<IReadOnlyList<EntityChange>> handler) : IDisposable
    {
        public void Dispose() => bus.Unsubscribe(handler);
    }
}
