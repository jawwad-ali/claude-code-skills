// ===========================================
// EXAMPLE: Basic Server Actions
// File: app/actions.ts
// ===========================================

'use server'

import { revalidatePath, revalidateTag } from 'next/cache'
import { redirect } from 'next/navigation'
import { z } from 'zod'

// ===========================================
// Example 1: Simple Form Action
// ===========================================

export async function subscribe(formData: FormData) {
  const email = formData.get('email') as string

  // Add to newsletter
  await db.subscriber.create({
    data: { email },
  })

  revalidatePath('/newsletter')
}

// Usage in component:
// <form action={subscribe}>
//   <input type="email" name="email" required />
//   <button type="submit">Subscribe</button>
// </form>

// ===========================================
// Example 2: Action with Validation
// ===========================================

const CreatePostSchema = z.object({
  title: z.string().min(1, 'Title is required').max(100),
  content: z.string().min(10, 'Content must be at least 10 characters'),
  published: z.boolean().default(false),
})

export type CreatePostState = {
  success?: boolean
  errors?: {
    title?: string[]
    content?: string[]
    published?: string[]
    _form?: string[]
  }
}

export async function createPost(
  prevState: CreatePostState,
  formData: FormData
): Promise<CreatePostState> {
  // Validate input
  const validatedFields = CreatePostSchema.safeParse({
    title: formData.get('title'),
    content: formData.get('content'),
    published: formData.get('published') === 'on',
  })

  if (!validatedFields.success) {
    return {
      errors: validatedFields.error.flatten().fieldErrors,
    }
  }

  try {
    await db.post.create({
      data: validatedFields.data,
    })

    revalidateTag('posts')
    return { success: true }
  } catch (error) {
    return {
      errors: {
        _form: ['Failed to create post. Please try again.'],
      },
    }
  }
}

// ===========================================
// Example 3: Action with Redirect
// ===========================================

export async function createAndRedirect(formData: FormData) {
  const title = formData.get('title') as string
  const content = formData.get('content') as string

  const post = await db.post.create({
    data: { title, content },
  })

  revalidatePath('/posts')
  redirect(`/posts/${post.id}`)
}

// ===========================================
// Example 4: Delete Action
// ===========================================

export async function deletePost(postId: string) {
  await db.post.delete({
    where: { id: postId },
  })

  revalidateTag('posts')
  redirect('/posts')
}

// Usage:
// <form action={deletePost.bind(null, post.id)}>
//   <button type="submit">Delete</button>
// </form>

// ===========================================
// Example 5: Toggle Action
// ===========================================

export async function togglePublished(postId: string, published: boolean) {
  await db.post.update({
    where: { id: postId },
    data: { published: !published },
  })

  revalidateTag('posts')
  revalidatePath(`/posts/${postId}`)
}

// ===========================================
// Example 6: Action with Authentication
// ===========================================

import { auth } from '@/lib/auth'

export async function createComment(postId: string, formData: FormData) {
  const session = await auth()

  if (!session?.user) {
    throw new Error('You must be logged in to comment')
  }

  const content = formData.get('content') as string

  await db.comment.create({
    data: {
      content,
      postId,
      authorId: session.user.id,
    },
  })

  revalidatePath(`/posts/${postId}`)
}

// ===========================================
// Example 7: Optimistic Update Support
// ===========================================

export async function likePost(postId: string) {
  const session = await auth()

  if (!session?.user) {
    throw new Error('You must be logged in to like posts')
  }

  await db.like.create({
    data: {
      postId,
      userId: session.user.id,
    },
  })

  revalidateTag(`post-${postId}`)
}

// ===========================================
// EXAMPLE: Client Component Using Server Action
// File: components/post-form.tsx
// ===========================================

// 'use client'

import { useActionState } from 'react'
import { createPost, type CreatePostState } from '@/app/actions'

const initialState: CreatePostState = {}

export function PostForm() {
  const [state, formAction, pending] = useActionState(createPost, initialState)

  return (
    <form action={formAction} className="space-y-4">
      {state.errors?._form && (
        <div className="p-4 bg-red-50 text-red-600 rounded">
          {state.errors._form.join(', ')}
        </div>
      )}

      <div>
        <label htmlFor="title" className="block font-medium">
          Title
        </label>
        <input
          id="title"
          name="title"
          className="w-full border rounded px-3 py-2"
          disabled={pending}
        />
        {state.errors?.title && (
          <p className="text-red-500 text-sm mt-1">
            {state.errors.title.join(', ')}
          </p>
        )}
      </div>

      <div>
        <label htmlFor="content" className="block font-medium">
          Content
        </label>
        <textarea
          id="content"
          name="content"
          rows={5}
          className="w-full border rounded px-3 py-2"
          disabled={pending}
        />
        {state.errors?.content && (
          <p className="text-red-500 text-sm mt-1">
            {state.errors.content.join(', ')}
          </p>
        )}
      </div>

      <div className="flex items-center gap-2">
        <input
          type="checkbox"
          id="published"
          name="published"
          disabled={pending}
        />
        <label htmlFor="published">Publish immediately</label>
      </div>

      <button
        type="submit"
        disabled={pending}
        className="px-4 py-2 bg-blue-500 text-white rounded disabled:opacity-50"
      >
        {pending ? 'Creating...' : 'Create Post'}
      </button>

      {state.success && (
        <p className="text-green-600">Post created successfully!</p>
      )}
    </form>
  )
}

// ===========================================
// EXAMPLE: Optimistic UI Component
// File: components/like-button.tsx
// ===========================================

// 'use client'

import { useOptimistic, useTransition } from 'react'
import { likePost } from '@/app/actions'

interface LikeButtonProps {
  postId: string
  initialLikes: number
  isLiked: boolean
}

export function LikeButton({ postId, initialLikes, isLiked }: LikeButtonProps) {
  const [isPending, startTransition] = useTransition()

  const [optimisticState, addOptimistic] = useOptimistic(
    { likes: initialLikes, isLiked },
    (state, _) => ({
      likes: state.isLiked ? state.likes - 1 : state.likes + 1,
      isLiked: !state.isLiked,
    })
  )

  async function handleClick() {
    startTransition(async () => {
      addOptimistic(null)
      await likePost(postId)
    })
  }

  return (
    <button
      onClick={handleClick}
      disabled={isPending}
      className={`flex items-center gap-2 px-3 py-1 rounded ${
        optimisticState.isLiked
          ? 'bg-red-100 text-red-600'
          : 'bg-gray-100 text-gray-600'
      }`}
    >
      <span>{optimisticState.isLiked ? '❤️' : '🤍'}</span>
      <span>{optimisticState.likes}</span>
    </button>
  )
}

// Mock db for example purposes
declare const db: {
  subscriber: { create: (args: { data: { email: string } }) => Promise<void> }
  post: {
    create: (args: { data: any }) => Promise<{ id: string }>
    update: (args: { where: { id: string }; data: any }) => Promise<void>
    delete: (args: { where: { id: string } }) => Promise<void>
  }
  comment: { create: (args: { data: any }) => Promise<void> }
  like: { create: (args: { data: any }) => Promise<void> }
}
