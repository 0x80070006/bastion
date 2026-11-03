<script lang="ts">
  // Stroke icons from Lucide (ISC licence), one family at one weight.
  type Shape = { tag: "rect" | "path" | "line" } & Record<string, string>;

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
