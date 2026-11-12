<script setup>
defineProps({ name: String, size: { type: Number, default: 20 } })
const paths = {
  bus: 'M4 16V5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v11M4 11h16M4 16h16M7 16v3M17 16v3M7 7h.01M17 7h.01M8 3v8M7 14h.01M17 14h.01',
  route:
    'M6 4a2 2 0 1 0 0 4 2 2 0 0 0 0-4Zm12 12a2 2 0 1 0 0 4 2 2 0 0 0 0-4ZM6 8v7a3 3 0 0 0 3 3h7M18 16V9a3 3 0 0 0-3-3H8',
  clock: 'M12 8v5l3 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  pin: 'M20 10c0 6-8 11-8 11S4 16 4 10a8 8 0 1 1 16 0ZM15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z',
  users:
    'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM22 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8',
  grid: 'M3 3h7v7H3ZM14 3h7v7h-7ZM3 14h7v7H3ZM14 14h7v7h-7Z',
  alert: 'm12 3 10 18H2L12 3ZM12 9v5M12 17h.01',
  task: 'M9 4H5v17h14V4h-4M9 2h6v4H9ZM8 11h8M8 15h6',
  refresh: 'M20 7V3l-3 3a8 8 0 1 0 2 10M20 3h-4',
  arrow: 'M5 12h14m-5-5 5 5-5 5',
  check: 'm5 12 4 4L19 6',
  bell: 'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4',
  history: 'M3 12a9 9 0 1 0 2-5M3 3v5h5M12 7v6l3 2',
  exit: 'M9 4H4v16h5M12 12h9m-4-4 4 4-4 4',
}
</script>
<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="paths[name] || paths.bus" />
  </svg>
</template>
