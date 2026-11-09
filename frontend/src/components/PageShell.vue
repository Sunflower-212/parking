<script setup>
defineProps({ title: String })
</script>
<template>
  <section class="page-shell">
    <header class="page-heading">
      <div>
        <h1>{{ title }}</h1>
      </div>
      <div class="page-actions"><slot name="actions" /></div>
    </header>
    <slot />
  </section>
</template>
