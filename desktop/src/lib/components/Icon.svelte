<script lang="ts">
  // Stroke icons from Lucide (ISC licence), one family at one weight.
  type Shape = { tag: "rect" | "path" | "line" | "circle" | "polyline" } & Record<string, string>;

  const line = (x1: number, x2: number, y1: number, y2: number): Shape => ({
    tag: "line",
    x1: String(x1),
    x2: String(x2),
    y1: String(y1),
    y2: String(y2),
  });
  const path = (d: string): Shape => ({ tag: "path", d });

  const SHAPES = {
    devices: [
      { tag: "rect", width: "14", height: "20", x: "5", y: "2", rx: "2", ry: "2" },
      path("M12 18h.01"),
    ],
    journal: [
      path("M3 12h.01"),
      path("M3 18h.01"),
      path("M3 6h.01"),
      path("M8 12h13"),
      path("M8 18h13"),
      path("M8 6h13"),
    ],
    settings: [
      line(21, 14, 4, 4),
      line(10, 3, 4, 4),
      line(21, 12, 12, 12),
      line(8, 3, 12, 12),
      line(21, 16, 20, 20),
      line(12, 3, 20, 20),
      line(14, 14, 2, 6),
      line(8, 8, 10, 14),
      line(16, 16, 18, 22),
    ],
    lock: [
      { tag: "rect", width: "18", height: "11", x: "3", y: "11", rx: "2", ry: "2" },
      path("M7 11V7a5 5 0 0 1 10 0v4"),
    ],
    density: [path("M3 6h18"), path("M3 10h18"), path("M3 14h18"), path("M3 18h18")],
    overview: [
      path("M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z"),
      { tag: "circle", cx: "12", cy: "10", r: "3" },
    ],
    control: [{ tag: "polyline", points: "4 17 10 11 4 5" }, line(12, 20, 19, 19)],
    media: [
      path(
        "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z",
      ),
      { tag: "circle", cx: "12", cy: "13", r: "3" },
    ],
    comms: [path("M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z")],
    security: [
      path(
        "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
      ),
    ],
  } satisfies Record<string, Shape[]>;

  interface Props {
    name: keyof typeof SHAPES;
    size?: number;
  }

  let { name, size = 16 }: Props = $props();
</script>

<svg
  width={size}
  height={size}
  viewBox="0 0 24 24"
  fill="none"
  stroke="currentColor"
  stroke-width="1.75"
  stroke-linecap="round"
  stroke-linejoin="round"
  aria-hidden="true"
>
  {#each SHAPES[name] as { tag, ...attrs }, i (i)}
    <svelte:element this={tag} {...attrs} />
  {/each}
</svg>
