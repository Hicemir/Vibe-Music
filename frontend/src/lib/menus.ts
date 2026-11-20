import { core, Go } from './store.svelte'
import { hasArtist, splitArtists } from './util'
import type { MenuItem, Track } from './types'

export function trackMenu(t: Track, opts: { onRemove?: () => void; playlistId?: number } = {}): MenuItem[] {
  const items: MenuItem[] = [
    { label: 'Play next', icon: 'queue', action: () => Go.PlayNext([t.id]) },
    { label: 'Add to queue', icon: 'plus', action: () => Go.AddToQueue([t.id]) },
    { separator: true },
    { label: 'Add to playlist…', icon: 'library', action: () => (core.modal = { type: 'addToPlaylist', trackIds: [t.id] }) },
    {
      label: t.liked ? 'Remove from Liked Songs' : 'Add to Liked Songs',
      icon: t.liked ? 'heart-fill' : 'heart',
      action: () => core.toggleLike(t.id),
    },
  ]
  if (opts.onRemove) items.push({ label: 'Remove from this playlist', icon: 'close', action: opts.onRemove })
  items.push({ separator: true })
  const artists = splitArtists(t.artist).filter((a) => a && a !== 'Unknown Artist')
  if (artists.length === 1) {
    items.push({ label: 'Go to artist', icon: 'person', action: () => core.go({ name: 'artist', id: artists[0] }) })
  } else {
    for (const a of artists.slice(0, 3)) {
      items.push({ label: `Artist: ${a}`, icon: 'person', action: () => core.go({ name: 'artist', id: a }) })
    }
  }
  if (t.album) items.push({ label: 'Go to album', icon: 'album', action: () => core.go({ name: 'album', id: t.album }) })
  items.push({
    label: t.genres?.length
      ? `Edit genres (${t.genres.length <= 3 ? t.genres.join(', ') : `${t.genres.slice(0, 3).join(', ')} +${t.genres.length - 3}`})…`
      : 'Set genres…',
    icon: 'tag',
    action: () => (core.modal = { type: 'genre', trackIds: [t.id], label: t.title, current: t.genres ?? [] }),
  })
  if (artists.length > 0) {
    const a0 = artists[0]
    items.push({
      label: `Set genres for all by ${a0}…`,
      icon: 'tag',
      action: () =>
        (core.modal = {
          type: 'genre',
          trackIds: core.tracks.filter((x) => hasArtist(x, a0)).map((x) => x.id),
          label: `all songs by ${a0}`,
          current: t.genres ?? [],
        }),
    })
  }
  items.push({ label: 'Show in file manager', icon: 'folder', action: () => Go.ShowInFolder(t.id) })
  items.push({
    label: 'Move file to trash',
    icon: 'trash',
    danger: true,
    action: () =>
      (core.modal = {
        type: 'confirm',
        title: 'Move to trash?',
        message: `“${t.title}” will be moved to the trash and removed from your library.`,
        confirmLabel: 'Move to trash',
        danger: true,
        onConfirm: async () => {
          const err = await Go.TrashTrack(t.id)
          if (err) core.toast(err)
        },
      }),
  })
  return items
}

export function tracksMenu(ids: string[]): MenuItem[] {
  return [
    { label: 'Play next', icon: 'queue', action: () => Go.PlayNext(ids) },
    { label: 'Add to queue', icon: 'plus', action: () => Go.AddToQueue(ids) },
    { label: 'Add to playlist…', icon: 'library', action: () => (core.modal = { type: 'addToPlaylist', trackIds: ids }) },
  ]
}
