<script setup lang="ts">
import type { SongDetail, Comment } from '@/api/interface'
import { ref, inject, type Ref, computed, watch } from 'vue'
import { formatNumber } from '@/utils'
import coverImg from '@/assets/cover.png'
import {
  likeComment,
  addSongComment,
  getSongComments,
  deleteComment,
} from '@/api/system'
import { ElMessage } from 'element-plus'
import { UserStore } from '@/stores/modules/user'

const songDetail = inject<Ref<SongDetail | null>>('songDetail', ref(null))
const userStore = UserStore()

// 获取当前用户名
const currentUsername = computed(() => userStore.userInfo?.username || '')

// 评论相关
const commentContent = ref('')
const maxLength = 180

const comments = ref<Comment[]>([])
const commentTotal = ref(0)
const commentPage = ref(1)
const commentsLoading = ref(false)
let commentRequestId = 0

const loadComments = async (page = 1) => {
  const songId = songDetail.value?.songId
  if (!songId) return
  const requestId = ++commentRequestId
  commentsLoading.value = true
  try {
    const response = await getSongComments(songId, page, 20)
    if (requestId !== commentRequestId || songId !== songDetail.value?.songId) return
    if (response.code !== 0 || !response.data) throw new Error('comments')
    comments.value = response.data.items as Comment[]
    commentTotal.value = response.data.total ?? 0
    commentPage.value = page
  } catch {
    if (requestId === commentRequestId) ElMessage.error('评论加载失败，请重试')
  } finally {
    if (requestId === commentRequestId) commentsLoading.value = false
  }
}

watch(() => songDetail.value?.songId, () => {
  ++commentRequestId
  comments.value = []
  commentTotal.value = 0
  commentPage.value = 1
  commentsLoading.value = false
  loadComments(1)
}, { immediate: true })

// 发布评论
const handleComment = async () => {
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    return
  }

  if (!commentContent.value.trim()) {
    ElMessage.warning('请输入评论内容')
    return
  }

  try {
    const songId = songDetail.value?.songId
    if (!songId) return

    const content = commentContent.value.trim()
    const res = await addSongComment({
      songId,
      content,
    })

    if (res.code === 0) {
      ElMessage.success('评论发布成功')
      commentContent.value = ''
      if (songId === songDetail.value?.songId) await loadComments(1)
    } else {
      ElMessage.error('评论发布失败')
    }
  } catch (error) {
    ElMessage.error('评论发布失败')
  }
}

const formatDate = (date: string) => {
  return new Date(date).toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })
}

// 处理点赞
const handleLike = async (comment: any) => {
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    return
  }

  try {
    // 调用点赞接口
    const res = await likeComment(comment.commentId)
    if (res.code === 0) {
      const current = comments.value.find(item => item.commentId === comment.commentId)
      if (current) current.likeCount = (current.likeCount || 0) + 1

      ElMessage.success('点赞成功')
    }
  } catch (error) {
    ElMessage.error('点赞失败')
  }
}

// 删除评论
const handleDelete = async (comment: any) => {
  try {
    const res = await deleteComment(comment.commentId)
    if (res.code === 0) {
      ElMessage.success('删除成功')
      const page = Math.max(1, Math.min(commentPage.value, Math.ceil((commentTotal.value - 1) / 20)))
      await loadComments(page)
    } else {
      ElMessage.error('删除失败')
    }
  } catch (error) {
    ElMessage.error('删除失败')
  }
}
</script>

<template>
  <div class="h-full p-6 overflow-y-auto mr-16">
    <div v-if="songDetail" class="space-y-6">
      <!-- 歌曲信息 -->
      <div class="space-y-2">
        <h3 class="text-xl font-semibold text-primary-foreground">歌曲信息</h3>
        <div class="grid grid-cols-2 gap-4 text-sm text-muted-foreground">
          <div>
            <span class="text-primary-foreground">专辑：</span>
            {{ songDetail.album }}
          </div>
          <div>
            <span class="text-primary-foreground">发行时间：</span>
            {{ formatDate(songDetail.releaseTime) }}
          </div>
        </div>
      </div>

      <!-- 评论区 -->
      <div class="space-y-4">
        <h3 class="text-xl font-semibold text-primary-foreground mt-12">
          评论（{{ formatNumber(commentTotal) }}）
        </h3>

        <!-- 评论输入框 -->
        <div class="mb-4">
          <div class="flex items-start gap-3">
            <div class="flex-1">
              <el-input
                v-model="commentContent"
                type="textarea"
                :rows="4"
                :maxlength="maxLength"
                placeholder="说点什么吧"
                resize="none"
                show-word-limit
              />
              <div class="flex justify-end items-center mt-4">
                <button
                  @click="handleComment"
                  :disabled="!commentContent.trim()"
                  class="px-6 py-1.5 bg-primary text-white rounded-full text-sm disabled:opacity-50 disabled:cursor-not-allowed hover:bg-primary/90 transition-colors"
                >
                  发布
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- 评论列表 -->
        <div v-if="comments.length > 0" class="space-y-4">
          <template v-for="comment in comments" :key="comment.commentId">
            <div class="flex gap-3 group">
              <div
                class="w-10 h-10 rounded-full overflow-hidden flex-shrink-0 mt-0.5"
              >
                <img
                  :src="comment.userAvatar || coverImg"
                  alt="avatar"
                  class="w-full h-full object-cover"
                />
              </div>
              <div class="flex-1">
                <div class="flex items-center gap-2">
                  <span class="text-sm font-medium text-primary">{{
                    comment.username
                  }}</span>
                </div>
                <p class="text-sm mt-1 mb-2">{{ comment.content }}</p>
                <div
                  class="flex items-center justify-between text-sm text-muted-foreground"
                >
                  <span class="text-xs">{{ comment.createTime }}</span>
                  <div class="flex items-center gap-4">
                    <!-- 如果是用户自己的评论，显示删除按钮 -->
                    <button
                      v-if="comment.username === currentUsername"
                      class="flex items-center gap-1 hover:text-red-500 opacity-0 group-hover:opacity-100 transition-opacity"
                      @click="handleDelete(comment)"
                    >
                      <icon-material-symbols:delete-outline />
                      <span>删除</span>
                    </button>
                    <button
                      class="flex items-center gap-1 hover:text-primary"
                      @click="handleLike(comment)"
                    >
                      <span>{{ formatNumber(comment.likeCount) }}</span>
                      <icon-material-symbols:thumb-up />
                    </button>
                  </div>
                </div>
              </div>
            </div>
            <div class="border-b border-border"></div>
          </template>
        </div>
        <div v-else class="text-center py-8 text-muted-foreground">
          <p>{{ commentsLoading ? '评论加载中…' : '暂无评论，快来抢沙发吧~' }}</p>
        </div>
        <el-pagination
          v-if="commentTotal > 20"
          :current-page="commentPage" :page-size="20" :total="commentTotal"
          :disabled="commentsLoading" layout="prev, pager, next"
          @current-change="loadComments"
        />
      </div>
    </div>
    <div v-else class="flex items-center justify-center h-full">
      <el-empty description="暂无歌曲信息" />
    </div>
  </div>
</template>

<style scoped>
.el-button {
  --el-button-hover-text-color: var(--el-color-primary);
  --el-button-hover-bg-color: transparent;
}

:deep(.el-input__wrapper) {
  border-radius: 8px;
}

:deep(.el-textarea__inner) {
  border-radius: 12px !important;
}
</style>
