-- Mixtape cloud sync schema.
-- Run once in your Supabase project: Dashboard > SQL Editor > New query > paste > Run.
-- Every table is private per user (Row Level Security): people only ever see their own rows.

create table if not exists public.library_tracks (
  user_id       uuid    not null default auth.uid() references auth.users (id) on delete cascade,
  track_key     text    not null,
  source        text    not null,
  source_id     text    not null,
  title         text    not null default '',
  artist        text    not null default '',
  album         text,
  duration_sec  bigint  not null default 0,
  artwork_url   text,
  isrc          text,
  liked         boolean not null default false,
  liked_at      bigint  not null default 0,
  downloaded    boolean not null default false,
  downloaded_at bigint  not null default 0,
  primary key (user_id, track_key)
);

create table if not exists public.playlists (
  user_id     uuid    not null default auth.uid() references auth.users (id) on delete cascade,
  sync_id     text    not null,
  name        text    not null,
  origin      text    not null default 'local',
  external_id text,
  artwork_url text,
  created_at  bigint  not null default 0,
  updated_at  bigint  not null default 0,
  deleted     boolean not null default false,
  primary key (user_id, sync_id)
);

create table if not exists public.playlist_items (
  user_id          uuid not null default auth.uid() references auth.users (id) on delete cascade,
  playlist_sync_id text not null,
  position         int  not null,
  track_key        text not null,
  primary key (user_id, playlist_sync_id, position)
);

create table if not exists public.track_matches (
  user_id     uuid    not null default auth.uid() references auth.users (id) on delete cascade,
  spotify_key text    not null,
  youtube_id  text    not null,
  manual      boolean not null default false,
  primary key (user_id, spotify_key)
);

alter table public.library_tracks enable row level security;
alter table public.playlists      enable row level security;
alter table public.playlist_items enable row level security;
alter table public.track_matches  enable row level security;

drop policy if exists "own rows" on public.library_tracks;
drop policy if exists "own rows" on public.playlists;
drop policy if exists "own rows" on public.playlist_items;
drop policy if exists "own rows" on public.track_matches;

create policy "own rows" on public.library_tracks for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own rows" on public.playlists      for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own rows" on public.playlist_items for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own rows" on public.track_matches  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
