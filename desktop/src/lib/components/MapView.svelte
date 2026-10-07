<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import L from "leaflet";
  import "leaflet/dist/leaflet.css";
  import type { LocationPoint } from "../api";
  import { tileUrlTemplate } from "../format";

  interface Props {
    points: LocationPoint[];
    label: string;
  }

  let { points, label }: Props = $props();
  let container: HTMLDivElement | undefined = $state();
  let map: L.Map | undefined;
  let layer: L.LayerGroup | undefined;
  let lastFitted = "";

  onMount(() => {
    if (!container) return;
    map = L.map(container, { zoomControl: true, attributionControl: true, worldCopyJump: true });
    L.tileLayer(tileUrlTemplate(navigator.userAgent), {
      maxZoom: 19,
      attribution: "© OpenStreetMap",
    }).addTo(map);
    layer = L.layerGroup().addTo(map);
    map.setView([46.6, 2.4], 5);
  });

  onDestroy(() => map?.remove());

  $effect(() => {
    if (!map || !layer) return;
    layer.clearLayers();
    const last = points.at(-1);
    if (!last) return;
    const track = points.slice(-200).map((p) => L.latLng(p.latitude, p.longitude));
    if (track.length > 1) {
      L.polyline(track, { color: "#9a9aa3", weight: 2, opacity: 0.6 }).addTo(layer);
    }
    const here = L.latLng(last.latitude, last.longitude);
    L.circle(here, {
      radius: Math.max(last.accuracyM, 5),
      color: "#c9b27c",
      weight: 1,
      fillOpacity: 0.12,
    }).addTo(layer);
    L.circleMarker(here, {
      radius: 7,
      color: "#0a0a0b",
      weight: 2,
      fillColor: "#c9b27c",
      fillOpacity: 1,
    })
      .bindTooltip(label)
      .addTo(layer);
    // Re-center only when a new fix arrives, not on every refresh.
    const key = `${last.fixTimeMs}:${last.latitude}:${last.longitude}`;
    if (key !== lastFitted) {
      lastFitted = key;
      map.setView(here, Math.max(map.getZoom(), 15));
    }
  });
</script>

<div class="map" bind:this={container} role="img" aria-label={label}></div>

<style>
  .map {
    width: 100%;
    height: 100%;
    min-height: 280px;
    border-radius: var(--radius-md);
    border: var(--border-width) solid var(--color-border);
    background: var(--color-surface);
  }

  .map :global(.leaflet-container) {
    background: var(--color-surface);
  }

  .map :global(.leaflet-tile-pane) {
    filter: grayscale(0.6) brightness(0.75) contrast(1.1);
  }
</style>
