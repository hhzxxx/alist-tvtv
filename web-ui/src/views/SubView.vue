<template>
  <div class="subscription">
    <h2>TvBox订阅地址</h2>
    <div class="description">
      <a :href="currentUrl+'/sub/'+id" target="_blank">{{ currentUrl }}/sub/{{ id }}</a>
    </div>

    <h2>API返回数据</h2>
    <div class="data" v-loading="loading">
      <pre><code>{{ config }}</code></pre>
    </div>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref, watch} from 'vue'
import axios from "axios"
import {useRoute} from "vue-router";

const route = useRoute()
const loading = ref(false)
const id = ref('')
const config = ref('')
const currentUrl = window.location.origin

const load = (id: any) => {
  loading.value = true
  config.value = ''
  return axios.get('/sub/' + id).then(({data}) => {
    loading.value = false
    config.value = data
    return data
  })
}

watch(
  () => route.params.id,
  async newId => {
    id.value = newId as string
    config.value = await load(newId)
  }
)

onMounted(async () => {
  id.value = route.params.id as string
  config.value = await load(id.value)
})

</script>

<style scoped>
.description {
  margin-bottom: 12px;
}
</style>
