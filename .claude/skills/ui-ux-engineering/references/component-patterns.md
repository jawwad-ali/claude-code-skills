# Component Patterns: Tailwind CSS + Shadcn/UI

Detailed patterns for the components listed in `SKILL.md`. Every pattern follows the same rules:

- Shadcn/UI components first, custom code only when Shadcn has nothing.
- Colors only through Shadcn CSS variables (`bg-background`, `text-muted-foreground`, `border`, ...).
- Tailwind spacing scale only, no arbitrary values.
- Icons from `lucide-react`.
- Every data-driven component ships loading, empty and error states.

Component code uses arrow functions and named exports. Paths assume the default Shadcn alias `@/components/ui/*`.

---

## Table of contents

1. [Buttons](#1-buttons)
2. [Forms](#2-forms)
3. [Tables](#3-tables)
4. [Dialogs and AlertDialogs](#4-dialogs-and-alertdialogs)
5. [Cards](#5-cards)
6. [Sidebar navigation](#6-sidebar-navigation)
7. [Toasts](#7-toasts)
8. [Dashboards](#8-dashboards)
9. [Search](#9-search)
10. [Combobox and Select](#10-combobox-and-select)
11. [File upload](#11-file-upload)
12. [Pagination](#12-pagination)
13. [Tabs](#13-tabs)
14. [Empty states](#14-empty-states)
15. [Loading patterns](#15-loading-patterns)

---

## 1. Buttons

### Variants: pick by intent, not by looks

| Intent | Variant | Example |
|---|---|---|
| The one main action on a screen | `default` | "Create batch" |
| Secondary action next to a primary | `outline` | "Cancel", "Export" |
| Low-emphasis action in toolbars, rows | `ghost` | Row "Edit" icon button |
| Irreversible action (inside a confirm) | `destructive` | "Delete batch" |
| Inline text-like action | `link` | "View details" |

One `default` button per view. Two primary buttons side by side means the screen hasn't decided what matters.

### Loading button

```tsx
import { Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";

type SubmitButtonProps = {
  isPending: boolean;
  label: string;
  pendingLabel: string;
};

export const SubmitButton = ({ isPending, label, pendingLabel }: SubmitButtonProps) => (
  <Button type="submit" disabled={isPending} aria-busy={isPending}>
    {isPending ? (
      <>
        <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
        {pendingLabel}
      </>
    ) : (
      label
    )}
  </Button>
);
```

- Keep the button width stable: the pending label should be about as long as the normal one ("Save" → "Saving...").
- `disabled` during the request also blocks double submits.

### Icon placement

```tsx
<Button>
  <Plus className="h-4 w-4" aria-hidden="true" />
  New transfer
</Button>

<Button variant="outline">
  Next
  <ArrowRight className="h-4 w-4" aria-hidden="true" />
</Button>
```

- Leading icon for actions ("New", "Upload"), trailing icon for direction ("Next", external links).
- Icon-only buttons need an accessible name and a tooltip:

```tsx
<Tooltip>
  <TooltipTrigger asChild>
    <Button variant="ghost" size="icon" aria-label="Edit batch">
      <Pencil className="h-4 w-4" aria-hidden="true" />
    </Button>
  </TooltipTrigger>
  <TooltipContent>Edit batch</TooltipContent>
</Tooltip>
```

### Disabled with a reason

A disabled button swallows pointer events, so wrap it in a `span` for the tooltip to work:

```tsx
<Tooltip>
  <TooltipTrigger asChild>
    <span tabIndex={0}>
      <Button disabled>Send transfer</Button>
    </span>
  </TooltipTrigger>
  <TooltipContent>Select at least one pack</TooltipContent>
</Tooltip>
```

---

## 2. Forms

Always `react-hook-form` + `zod` + Shadcn `Form` components. Share the Zod schema with the API when possible.

```tsx
"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod";
import { Button } from "@/components/ui/button";
import {
  Form, FormControl, FormDescription, FormField, FormItem, FormLabel, FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";

const batchSchema = z.object({
  batchNumber: z.string().trim().min(1, "Batch number is required").max(40),
  expiryDate: z.string().min(1, "Expiry date is required"),
});

type BatchFormValues = z.infer<typeof batchSchema>;

type BatchFormProps = {
  onCreate: (values: BatchFormValues) => Promise<void>;
};

export const BatchForm = ({ onCreate }: BatchFormProps) => {
  const form = useForm<BatchFormValues>({
    resolver: zodResolver(batchSchema),
    defaultValues: { batchNumber: "", expiryDate: "" },
    mode: "onTouched",
  });

  const onSubmit = async (values: BatchFormValues) => {
    try {
      await onCreate(values);
      toast.success("Batch created", { description: values.batchNumber });
      form.reset();
    } catch (error) {
      form.setError("root", {
        message: error instanceof Error ? error.message : "Something went wrong. Try again.",
      });
    }
  };

  const { isSubmitting } = form.formState;

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6" noValidate>
        <FormField
          control={form.control}
          name="batchNumber"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Batch number</FormLabel>
              <FormControl>
                <Input placeholder="e.g. PCM-2026-0412" autoComplete="off" {...field} />
              </FormControl>
              <FormDescription>Printed on every pack in this batch.</FormDescription>
              <FormMessage />
            </FormItem>
          )}
        />

        <FormField
          control={form.control}
          name="expiryDate"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Expiry date</FormLabel>
              <FormControl>
                <Input type="date" {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        {form.formState.errors.root && (
          <p role="alert" className="text-sm text-destructive">
            {form.formState.errors.root.message}
          </p>
        )}

        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={() => form.reset()}>
            Reset
          </Button>
          <SubmitButton isPending={isSubmitting} label="Create batch" pendingLabel="Creating..." />
        </div>
      </form>
    </Form>
  );
};
```

Rules:
- `mode: "onTouched"` validates after the user leaves a field, not on the first keystroke.
- Server errors on a field: `form.setError("fieldName", { message })`. Errors for the whole form: `form.setError("root", ...)`.
- Never clear the user's input on a failed submit.
- Placeholders show an example, never the label.
- Group long forms into sections with a heading and `Separator`. Single column on mobile; two columns (`md:grid-cols-2`) only for short, related fields (first/last name, city/postcode).

---

## 3. Tables

`@tanstack/react-table` for logic, Shadcn `Table` for markup. For real data, sorting, filtering and pagination happen on the server and live in the URL.

### Column definitions

```tsx
import type { ColumnDef } from "@tanstack/react-table";
import { ArrowUpDown, MoreHorizontal } from "lucide-react";

export type Batch = {
  id: string;
  batchNumber: string;
  productName: string;
  expiryDate: string;
  status: "active" | "recalled";
};

export const batchColumns: ColumnDef<Batch>[] = [
  {
    id: "select",
    header: ({ table }) => (
      <Checkbox
        checked={table.getIsAllPageRowsSelected() || (table.getIsSomePageRowsSelected() && "indeterminate")}
        onCheckedChange={(value) => table.toggleAllPageRowsSelected(!!value)}
        aria-label="Select all rows"
      />
    ),
    cell: ({ row }) => (
      <Checkbox
        checked={row.getIsSelected()}
        onCheckedChange={(value) => row.toggleSelected(!!value)}
        aria-label={`Select ${row.original.batchNumber}`}
      />
    ),
    enableSorting: false,
  },
  {
    accessorKey: "batchNumber",
    header: ({ column }) => (
      <Button variant="ghost" size="sm" className="-ml-3" onClick={() => column.toggleSorting()}>
        Batch
        <ArrowUpDown className="h-3.5 w-3.5" aria-hidden="true" />
      </Button>
    ),
    cell: ({ row }) => <span className="font-mono text-sm">{row.original.batchNumber}</span>,
  },
  {
    accessorKey: "productName",
    header: "Product",
    cell: ({ row }) => <span className="block max-w-64 truncate">{row.original.productName}</span>,
  },
  {
    accessorKey: "expiryDate",
    header: () => <div className="text-right">Expiry</div>,
    cell: ({ row }) => <div className="text-right tabular-nums">{row.original.expiryDate}</div>,
  },
  {
    accessorKey: "status",
    header: "Status",
    cell: ({ row }) => <BatchStatusBadge status={row.original.status} />,
  },
  {
    id: "actions",
    cell: ({ row }) => (
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon" aria-label={`Actions for ${row.original.batchNumber}`}>
            <MoreHorizontal className="h-4 w-4" aria-hidden="true" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem>View packs</DropdownMenuItem>
          <DropdownMenuItem>Export QR labels</DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem className="text-destructive focus:text-destructive">Recall batch</DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    ),
  },
];
```

### Table rendering with every state

```tsx
type DataTableProps<T> = {
  table: TanstackTable<T>;
  isLoading: boolean;
  error: Error | null;
  onRetry: () => void;
  emptyState: React.ReactNode;
};

export const DataTable = <T,>({ table, isLoading, error, onRetry, emptyState }: DataTableProps<T>) => {
  const columnCount = table.getAllColumns().length;

  if (error) {
    return <ErrorState title="Couldn't load data" description={error.message} onRetry={onRetry} />;
  }

  return (
    <div className="rounded-lg border">
      <Table>
        <TableHeader>
          {table.getHeaderGroups().map((group) => (
            <TableRow key={group.id}>
              {group.headers.map((header) => (
                <TableHead key={header.id}>
                  {header.isPlaceholder ? null : flexRender(header.column.columnDef.header, header.getContext())}
                </TableHead>
              ))}
            </TableRow>
          ))}
        </TableHeader>
        <TableBody>
          {isLoading ? (
            Array.from({ length: 8 }).map((_, i) => (
              <TableRow key={i}>
                {Array.from({ length: columnCount }).map((__, j) => (
                  <TableCell key={j}>
                    <Skeleton className="h-4 w-full max-w-32" />
                  </TableCell>
                ))}
              </TableRow>
            ))
          ) : table.getRowModel().rows.length === 0 ? (
            <TableRow>
              <TableCell colSpan={columnCount} className="h-48">
                {emptyState}
              </TableCell>
            </TableRow>
          ) : (
            table.getRowModel().rows.map((row) => (
              <TableRow key={row.id} data-state={row.getIsSelected() ? "selected" : undefined}>
                {row.getVisibleCells().map((cell) => (
                  <TableCell key={cell.id}>{flexRender(cell.column.columnDef.cell, cell.getContext())}</TableCell>
                ))}
              </TableRow>
            ))
          )}
        </TableBody>
      </Table>
    </div>
  );
};
```

### Server-side table setup

```tsx
const table = useReactTable({
  data: data?.items ?? [],
  columns: batchColumns,
  pageCount: data?.pageCount ?? -1,
  state: { sorting, pagination, rowSelection },
  manualSorting: true,
  manualPagination: true,
  manualFiltering: true,
  onSortingChange: setSorting,       // writes to URL search params
  onPaginationChange: setPagination, // writes to URL search params
  onRowSelectionChange: setRowSelection,
  getRowId: (row) => row.id,         // keeps selection stable across pages
  getCoreRowModel: getCoreRowModel(),
});
```

Rules:
- Numbers and dates right-aligned with `tabular-nums`. IDs and codes in `font-mono`.
- Long text: `truncate` with a max width, full value in a `title` or tooltip.
- A toolbar above the table: search input on the left, filters and "Columns" visibility menu on the right. When rows are selected, the toolbar switches to a bulk-action bar ("3 selected · Transfer · Export · Clear").
- **Mobile:** below `md`, render each row as a stacked card (primary field as title, 2 to 3 secondary fields, actions menu) instead of a horizontally scrolling table.

---

## 4. Dialogs and AlertDialogs

### When to use which

| Need | Component |
|---|---|
| Short form or details without leaving the page | `Dialog` |
| Confirm an irreversible or risky action | `AlertDialog` |
| Long form, filters, or detail panel | `Sheet` (side="right") |
| Any dialog on mobile | `Drawer` / `Sheet side="bottom"` |

### Confirm destructive action

The title names the action and the object. The description says exactly what will happen. The confirm button repeats the verb.

```tsx
type RecallDialogProps = {
  batchNumber: string;
  packCount: number;
  onConfirm: () => Promise<void>;
};

export const RecallBatchDialog = ({ batchNumber, packCount, onConfirm }: RecallDialogProps) => {
  const [open, setOpen] = useState(false);
  const [isPending, setIsPending] = useState(false);

  const handleConfirm = async (event: React.MouseEvent) => {
    event.preventDefault(); // keep the dialog open until the request finishes
    setIsPending(true);
    try {
      await onConfirm();
      toast.success(`Batch ${batchNumber} recalled`);
      setOpen(false);
    } catch {
      toast.error("Recall failed", { description: "Nothing was changed. Try again." });
    } finally {
      setIsPending(false);
    }
  };

  return (
    <AlertDialog open={open} onOpenChange={setOpen}>
      <AlertDialogTrigger asChild>
        <Button variant="outline">Recall batch</Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Recall batch {batchNumber}?</AlertDialogTitle>
          <AlertDialogDescription>
            All {packCount.toLocaleString()} packs will be marked as recalled. Anyone who scans them will see a recall
            warning. This can't be undone.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={isPending}>Cancel</AlertDialogCancel>
          <AlertDialogAction
            onClick={handleConfirm}
            disabled={isPending}
            className={buttonVariants({ variant: "destructive" })}
          >
            {isPending && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
            Recall batch
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
};
```

### Responsive dialog (Dialog on desktop, Drawer on mobile)

```tsx
export const ResponsiveDialog = ({ open, onOpenChange, title, description, children }: ResponsiveDialogProps) => {
  const isDesktop = useMediaQuery("(min-width: 768px)");

  if (isDesktop) {
    return (
      <Dialog open={open} onOpenChange={onOpenChange}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
            {description && <DialogDescription>{description}</DialogDescription>}
          </DialogHeader>
          {children}
        </DialogContent>
      </Dialog>
    );
  }

  return (
    <Drawer open={open} onOpenChange={onOpenChange}>
      <DrawerContent>
        <DrawerHeader className="text-left">
          <DrawerTitle>{title}</DrawerTitle>
          {description && <DrawerDescription>{description}</DrawerDescription>}
        </DrawerHeader>
        <div className="px-4 pb-4">{children}</div>
      </DrawerContent>
    </Drawer>
  );
};
```

Rules:
- Every dialog has a `DialogTitle` (use `className="sr-only"` if it must be hidden visually).
- A dialog with a form doesn't close on outside click while the form is dirty: confirm "Discard changes?" first.
- Never stack dialogs. If a dialog needs another dialog, it should be a page or a `Sheet`.

---

## 5. Cards

### Static vs clickable

Only cards that navigate or open something get hover motion. Static info cards stay still.

```tsx
// Static
<Card>
  <CardHeader>
    <CardTitle className="text-base">Storage</CardTitle>
    <CardDescription>Keep below 25°C, away from light.</CardDescription>
  </CardHeader>
</Card>

// Clickable: the whole card is one link, with a visible focus ring
<Link
  href={`/products/${product.id}`}
  className="group block rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
>
  <Card className="h-full transition-all duration-200 group-hover:-translate-y-0.5 group-hover:shadow-md">
    <CardHeader>
      <CardTitle className="line-clamp-1 text-base">{product.name}</CardTitle>
      <CardDescription className="line-clamp-2">{product.description}</CardDescription>
    </CardHeader>
  </Card>
</Link>
```

### Card skeleton matching the real card

```tsx
export const ProductCardSkeleton = () => (
  <Card>
    <CardHeader className="space-y-2">
      <Skeleton className="h-5 w-2/3" />
      <Skeleton className="h-4 w-full" />
      <Skeleton className="h-4 w-4/5" />
    </CardHeader>
  </Card>
);
```

### Responsive grid

```tsx
<div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
  {isLoading
    ? Array.from({ length: 8 }).map((_, i) => <ProductCardSkeleton key={i} />)
    : products.map((p) => <ProductCard key={p.id} product={p} />)}
</div>
```

Rules:
- `h-full` on cards in a grid so a row lines up.
- Don't build entire pages out of cards. Lists of similar records belong in a table; a card is for a self-contained object or summary.
- Don't nest cards inside cards. Use `Separator` or a `bg-muted` section instead.

---

## 6. Sidebar navigation

Use the Shadcn `Sidebar` block. It handles the mobile `Sheet`, collapsing to icons, and keyboard shortcut (`Ctrl/Cmd + B`).

```tsx
"use client";

import { usePathname } from "next/navigation";

type NavItem = { title: string; href: string; icon: LucideIcon; badge?: number };

export const AppSidebar = ({ items }: { items: NavItem[] }) => {
  const pathname = usePathname();

  return (
    <Sidebar collapsible="icon">
      <SidebarHeader>
        <OrgSwitcher />
      </SidebarHeader>
      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupLabel>Operations</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              {items.map((item) => {
                const isActive = pathname === item.href || pathname.startsWith(`${item.href}/`);
                return (
                  <SidebarMenuItem key={item.href}>
                    <SidebarMenuButton asChild isActive={isActive} tooltip={item.title}>
                      <Link href={item.href} aria-current={isActive ? "page" : undefined}>
                        <item.icon aria-hidden="true" />
                        <span>{item.title}</span>
                      </Link>
                    </SidebarMenuButton>
                    {item.badge ? <SidebarMenuBadge>{item.badge}</SidebarMenuBadge> : null}
                  </SidebarMenuItem>
                );
              })}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>
      <SidebarFooter>
        <UserMenu />
      </SidebarFooter>
      <SidebarRail />
    </Sidebar>
  );
};
```

Layout:

```tsx
export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  return (
    <SidebarProvider>
      <AppSidebar items={navItems} />
      <SidebarInset>
        <header className="flex h-14 items-center gap-2 border-b px-4">
          <SidebarTrigger className="-ml-1" />
          <Separator orientation="vertical" className="mr-2 h-4" />
          <DashboardBreadcrumb />
          <div className="ml-auto flex items-center gap-2">
            <CommandSearchButton />
          </div>
        </header>
        <main className="flex-1">{children}</main>
      </SidebarInset>
    </SidebarProvider>
  );
}
```

(Next.js requires `export default` for layout and page files; everything else uses named exports.)

Rules:
- Active item: `isActive` plus `aria-current="page"`. Nested routes keep the parent active.
- 5 to 9 top-level items. Group the rest under labelled `SidebarGroup`s.
- Badges only for counts that need action (incoming transfers), not for decoration.
- Collapsed (icon) mode shows a tooltip with the item name.

---

## 7. Toasts

Use `sonner` (Shadcn's `toast` component is deprecated in favor of it). Mount `<Toaster />` once in the root layout.

```tsx
// app/layout.tsx
<Toaster position="bottom-right" richColors closeButton />
```

```tsx
import { toast } from "sonner";

toast.success("Transfer sent", { description: "48 packs to Al-Noor Distribution" });
toast.error("Couldn't send transfer", { description: "The receiver's account is suspended." });

// Async action with one toast that updates itself
toast.promise(sendTransfer(payload), {
  loading: "Sending transfer...",
  success: (t) => `Transfer ${t.reference} sent`,
  error: (e: Error) => e.message || "Couldn't send transfer",
});

// Undo instead of confirm, for reversible actions
toast("Pack removed from transfer", {
  action: { label: "Undo", onClick: () => restorePack(packId) },
});
```

When to use a toast:
- ✅ Result of an action the user just took ("Saved", "Sent", "Failed").
- ✅ Reversible destructive actions, with an Undo action.
- ❌ Form validation errors: show them next to the field.
- ❌ Errors that block the page: use an `Alert` in the page.
- ❌ Information the user must read later: toasts disappear.

Keep messages short: a title of 2 to 5 words and an optional one-line description. Never show raw error strings or stack traces.

---

## 8. Dashboards

### Page structure

```tsx
<div className="mx-auto max-w-7xl space-y-6 px-4 py-6 sm:px-6 sm:py-8 lg:px-8">
  <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
    <div>
      <h1 className="text-2xl font-semibold tracking-tight">Overview</h1>
      <p className="text-sm text-muted-foreground">Last 30 days</p>
    </div>
    <DateRangePicker />
  </div>

  <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">{/* stat cards */}</div>

  <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
    <Card className="lg:col-span-2">{/* main chart */}</Card>
    <Card>{/* recent activity */}</Card>
  </div>
</div>
```

### Stat card

```tsx
type StatCardProps = {
  label: string;
  value: string;
  change?: { value: number; period: string };
  icon: LucideIcon;
};

export const StatCard = ({ label, value, change, icon: Icon }: StatCardProps) => {
  const isUp = change ? change.value >= 0 : false;

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">{label}</CardTitle>
        <Icon className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
      </CardHeader>
      <CardContent>
        <p className="text-2xl font-semibold tabular-nums">{value}</p>
        {change && (
          <p className="mt-1 flex items-center gap-1 text-xs text-muted-foreground">
            {isUp ? (
              <TrendingUp className="h-3.5 w-3.5" aria-hidden="true" />
            ) : (
              <TrendingDown className="h-3.5 w-3.5" aria-hidden="true" />
            )}
            <span className="sr-only">{isUp ? "Up" : "Down"}</span>
            {Math.abs(change.value)}% vs {change.period}
          </p>
        )}
      </CardContent>
    </Card>
  );
};
```

### Charts

Use Shadcn `Chart` (Recharts with theme-aware colors through `--chart-1` … `--chart-5`).

```tsx
const chartConfig = {
  registered: { label: "Registered", color: "var(--chart-1)" },
  transferred: { label: "Transferred", color: "var(--chart-2)" },
} satisfies ChartConfig;

<ChartContainer config={chartConfig} className="h-72 w-full">
  <AreaChart data={data} margin={{ left: 0, right: 8 }}>
    <CartesianGrid vertical={false} />
    <XAxis dataKey="date" tickLine={false} axisLine={false} tickMargin={8} />
    <YAxis tickLine={false} axisLine={false} width={40} />
    <ChartTooltip content={<ChartTooltipContent indicator="line" />} />
    <Area dataKey="registered" type="monotone" fill="var(--color-registered)" stroke="var(--color-registered)" fillOpacity={0.15} />
    <Area dataKey="transferred" type="monotone" fill="var(--color-transferred)" stroke="var(--color-transferred)" fillOpacity={0.15} />
  </AreaChart>
</ChartContainer>
```

Rules:
- 4 stat cards max in the top row. Pick the numbers someone acts on, not every number you have.
- Every chart has a fixed height (so loading doesn't shift layout), a skeleton of the same height, and an empty state ("No transfers in this period").
- Each widget loads and fails on its own. One failed chart shows its own error, not a broken page.
- Don't use pie charts for more than 4 categories, or 3D charts at all.

---

## 9. Search

### Debounced search input synced to the URL

```tsx
"use client";

export const SearchInput = ({ placeholder }: { placeholder: string }) => {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const [value, setValue] = useState(searchParams.get("q") ?? "");
  const debounced = useDebounce(value, 300);

  useEffect(() => {
    const params = new URLSearchParams(searchParams);
    if (debounced) params.set("q", debounced);
    else params.delete("q");
    params.delete("page"); // new search starts on page 1
    router.replace(`${pathname}?${params.toString()}`, { scroll: false });
  }, [debounced]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="relative w-full sm:max-w-xs">
      <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
      <Input
        type="search"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        placeholder={placeholder}
        aria-label={placeholder}
        className="pl-8"
      />
    </div>
  );
};
```

### Command palette (Ctrl/Cmd + K)

```tsx
export const CommandMenu = () => {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "k" && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        setOpen((prev) => !prev);
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, []);

  return (
    <CommandDialog open={open} onOpenChange={setOpen}>
      <CommandInput placeholder="Search packs, batches, or go to..." />
      <CommandList>
        <CommandEmpty>No results found.</CommandEmpty>
        <CommandGroup heading="Go to">
          <CommandItem onSelect={() => navigate("/transfers")}>
            <ArrowLeftRight className="h-4 w-4" aria-hidden="true" />
            Transfers
          </CommandItem>
        </CommandGroup>
      </CommandList>
    </CommandDialog>
  );
};
```

Rules:
- Debounce 250 to 300ms. Cancel stale requests (TanStack Query does this through the query key).
- Show a trigger button in the header with the shortcut hint: `<kbd>⌘K</kbd>`.
- Empty results say what was searched and offer a way out: "No packs match 'MED-99'. Check the ID or clear the search."

---

## 10. Combobox and Select

| Options | Component |
|---|---|
| Up to ~7 fixed options | `Select` |
| More options, or the user benefits from typing | Combobox (`Popover` + `Command`) |
| Options come from the server | Async combobox |
| Several values | Multi-select combobox with badges |

### Combobox

```tsx
export const OrgCombobox = ({ orgs, value, onChange }: OrgComboboxProps) => {
  const [open, setOpen] = useState(false);
  const selected = orgs.find((o) => o.id === value);

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button variant="outline" role="combobox" aria-expanded={open} className="w-full justify-between font-normal">
          <span className={cn("truncate", !selected && "text-muted-foreground")}>
            {selected ? selected.name : "Select receiver"}
          </span>
          <ChevronsUpDown className="h-4 w-4 shrink-0 opacity-50" aria-hidden="true" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-[--radix-popover-trigger-width] p-0" align="start">
        <Command>
          <CommandInput placeholder="Search organizations..." />
          <CommandList>
            <CommandEmpty>No organization found.</CommandEmpty>
            <CommandGroup>
              {orgs.map((org) => (
                <CommandItem
                  key={org.id}
                  value={org.name}
                  onSelect={() => {
                    onChange(org.id);
                    setOpen(false);
                  }}
                >
                  <Check className={cn("h-4 w-4", value === org.id ? "opacity-100" : "opacity-0")} aria-hidden="true" />
                  <span className="truncate">{org.name}</span>
                  <span className="ml-auto text-xs text-muted-foreground">{org.city}</span>
                </CommandItem>
              ))}
            </CommandGroup>
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
};
```

### Async combobox

- `<Command shouldFilter={false}>` so the server does the filtering.
- Debounce the input, show `<Loader2>` inside the list while loading, and keep the previous results visible until new ones arrive (`placeholderData: keepPreviousData` in TanStack Query).

### Multi-select

- Show selected values as `Badge`s with a remove button (`aria-label="Remove {name}"`) above or inside the trigger.
- After 3 badges, collapse the rest into "+N more".

---

## 11. File upload

```tsx
"use client";

const MAX_SIZE = 5 * 1024 * 1024;
const ACCEPTED = ["text/csv", "application/vnd.ms-excel"];

export const FileDropzone = ({ onUpload }: { onUpload: (file: File, onProgress: (p: number) => void) => Promise<void> }) => {
  const [isDragging, setIsDragging] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [progress, setProgress] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const validate = (f: File): string | null => {
    if (!ACCEPTED.includes(f.type)) return "Only CSV files are supported.";
    if (f.size > MAX_SIZE) return "File is larger than 5 MB.";
    return null;
  };

  const handleFile = async (f: File) => {
    const message = validate(f);
    setError(message);
    if (message) return;
    setFile(f);
    setProgress(0);
    try {
      await onUpload(f, setProgress);
      toast.success("File uploaded", { description: f.name });
    } catch {
      setError("Upload failed. Check your connection and try again.");
    }
  };

  return (
    <div className="space-y-3">
      <div
        role="button"
        tabIndex={0}
        aria-label="Upload a CSV file"
        onClick={() => inputRef.current?.click()}
        onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && inputRef.current?.click()}
        onDragOver={(e) => {
          e.preventDefault();
          setIsDragging(true);
        }}
        onDragLeave={() => setIsDragging(false)}
        onDrop={(e) => {
          e.preventDefault();
          setIsDragging(false);
          const dropped = e.dataTransfer.files[0];
          if (dropped) void handleFile(dropped);
        }}
        className={cn(
          "flex cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed p-8 text-center transition-colors duration-200",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
          isDragging ? "border-primary bg-primary/5" : "border-border hover:bg-muted/50",
        )}
      >
        <UploadCloud className="h-8 w-8 text-muted-foreground" aria-hidden="true" />
        <p className="text-sm font-medium">Drop a CSV here or click to browse</p>
        <p className="text-xs text-muted-foreground">Up to 5 MB</p>
        <input
          ref={inputRef}
          type="file"
          accept=".csv"
          className="sr-only"
          onChange={(e) => e.target.files?.[0] && void handleFile(e.target.files[0])}
        />
      </div>

      {file && !error && (
        <div className="flex items-center gap-3 rounded-md border p-3">
          <FileText className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <div className="min-w-0 flex-1 space-y-1.5">
            <p className="truncate text-sm">{file.name}</p>
            <Progress value={progress} aria-label={`Upload progress ${progress}%`} />
          </div>
          <span className="text-xs tabular-nums text-muted-foreground">{progress}%</span>
        </div>
      )}

      {error && (
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>Can't upload this file</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
    </div>
  );
};
```

Rules:
- Validate type and size on the client for fast feedback, and again on the server.
- State the limits before the user picks a file ("CSV, up to 5 MB").
- Image uploads show a thumbnail preview (`URL.createObjectURL`, revoked on unmount).

---

## 12. Pagination

Page state lives in the URL so back/forward and shared links work.

```tsx
"use client";

type PaginationBarProps = { page: number; pageCount: number; total: number; pageSize: number };

export const PaginationBar = ({ page, pageCount, total, pageSize }: PaginationBarProps) => {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const goTo = (next: number) => {
    const params = new URLSearchParams(searchParams);
    params.set("page", String(next));
    router.push(`${pathname}?${params.toString()}`, { scroll: false });
  };

  const from = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const to = Math.min(page * pageSize, total);

  return (
    <div className="flex flex-col items-center justify-between gap-3 sm:flex-row">
      <p className="text-sm text-muted-foreground tabular-nums">
        {from.toLocaleString()}–{to.toLocaleString()} of {total.toLocaleString()}
      </p>
      <div className="flex items-center gap-2">
        <Button variant="outline" size="sm" onClick={() => goTo(page - 1)} disabled={page <= 1}>
          <ChevronLeft className="h-4 w-4" aria-hidden="true" />
          Previous
        </Button>
        <span className="text-sm tabular-nums">
          Page {page} of {Math.max(pageCount, 1)}
        </span>
        <Button variant="outline" size="sm" onClick={() => goTo(page + 1)} disabled={page >= pageCount}>
          Next
          <ChevronRight className="h-4 w-4" aria-hidden="true" />
        </Button>
      </div>
    </div>
  );
};
```

Rules:
- Clamp invalid `?page=` values on the server (page 0 or page 999 → nearest valid page).
- Reset to page 1 when search or filters change.
- Offer a page-size select (10 / 25 / 50) on data-heavy tables.
- Very large or live data (activity feeds): cursor-based "Load more" instead of page numbers.

---

## 13. Tabs

```tsx
"use client";

export const BatchTabs = ({ defaultTab = "packs" }: { defaultTab?: string }) => {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const tab = searchParams.get("tab") ?? defaultTab;

  const onTabChange = (value: string) => {
    const params = new URLSearchParams(searchParams);
    params.set("tab", value);
    router.replace(`${pathname}?${params.toString()}`, { scroll: false });
  };

  return (
    <Tabs value={tab} onValueChange={onTabChange}>
      <div className="overflow-x-auto">
        <TabsList>
          <TabsTrigger value="packs">Packs</TabsTrigger>
          <TabsTrigger value="history">History</TabsTrigger>
          <TabsTrigger value="settings">Settings</TabsTrigger>
        </TabsList>
      </div>
      <TabsContent value="packs" className="mt-6">
        <PacksPanel />
      </TabsContent>
      <TabsContent value="history" className="mt-6">
        <HistoryPanel />
      </TabsContent>
      <TabsContent value="settings" className="mt-6">
        <SettingsPanel />
      </TabsContent>
    </Tabs>
  );
};
```

Rules:
- Tabs switch between views of the same object. Different pages belong in navigation.
- The active tab is in the URL (`?tab=`), so refresh and shared links keep it.
- 2 to 6 tabs. On narrow screens the list scrolls horizontally instead of wrapping.
- Each panel loads its own data when opened and has its own loading/empty/error states.
- Use a count in the trigger only when it helps decide: "Incoming (3)".

---

## 14. Empty states

Three kinds of empty, three different messages:

| Kind | Example message | Action |
|---|---|---|
| First use (nothing created yet) | "No batches yet. Create your first batch to start registering packs." | Primary button: "Create batch" |
| No results (search/filter) | "No batches match 'PCM-04'." | Outline button: "Clear filters" |
| Done / nothing to do | "No incoming transfers. You're all caught up." | None, or a link to history |

```tsx
type EmptyStateProps = {
  icon: LucideIcon;
  title: string;
  description: string;
  action?: React.ReactNode;
};

export const EmptyState = ({ icon: Icon, title, description, action }: EmptyStateProps) => (
  <div className="flex flex-col items-center justify-center gap-3 px-4 py-12 text-center">
    <div className="flex h-12 w-12 items-center justify-center rounded-full bg-muted">
      <Icon className="h-6 w-6 text-muted-foreground" aria-hidden="true" />
    </div>
    <div className="max-w-sm space-y-1">
      <h3 className="text-base font-semibold">{title}</h3>
      <p className="text-sm text-muted-foreground">{description}</p>
    </div>
    {action}
  </div>
);
```

```tsx
<EmptyState
  icon={Package}
  title="No batches yet"
  description="Create your first batch to start registering packs and printing QR labels."
  action={
    <Button onClick={openCreate}>
      <Plus className="h-4 w-4" aria-hidden="true" />
      Create batch
    </Button>
  }
/>
```

Rules:
- Say what this area is for and what to do next. "No data" alone is never enough.
- Match the icon to the object (Package, ArrowLeftRight, QrCode), not a generic inbox.
- If the user lacks permission to create, say who can: "Ask an admin of your organization to add products."

---

## 15. Loading patterns

| Situation | Pattern |
|---|---|
| Page or section loading for the first time | `Skeleton` shaped like the real content |
| Route change in Next.js | `loading.tsx` with the page skeleton |
| Button action | `Loader2` inside the button, button disabled |
| Background refetch of data already on screen | Keep showing data; small `Loader2` next to the heading, no skeleton |
| Long task with known progress (upload, export) | `Progress` with percentage |
| Long task with unknown duration (> 2s) | Message that says what's happening: "Generating 1,000 QR labels..." |

### Route-level skeleton

```tsx
// app/(dashboard)/batches/loading.tsx
export default function Loading() {
  return (
    <div className="mx-auto max-w-7xl space-y-6 px-4 py-6 sm:px-6 sm:py-8 lg:px-8">
      <div className="flex items-center justify-between">
        <Skeleton className="h-8 w-40" />
        <Skeleton className="h-9 w-32" />
      </div>
      <div className="rounded-lg border">
        {Array.from({ length: 8 }).map((_, i) => (
          <div key={i} className="flex items-center gap-4 border-b p-4 last:border-b-0">
            <Skeleton className="h-4 w-4" />
            <Skeleton className="h-4 w-32" />
            <Skeleton className="h-4 flex-1" />
            <Skeleton className="h-4 w-20" />
          </div>
        ))}
      </div>
    </div>
  );
}
```

### Streaming sections with Suspense

```tsx
// Each section streams in on its own; a slow widget doesn't block the page.
<Suspense fallback={<StatCardsSkeleton />}>
  <StatCards />
</Suspense>
<Suspense fallback={<ChartSkeleton />}>
  <ActivityChart />
</Suspense>
```

### Error state (pairs with every loading state)

```tsx
type ErrorStateProps = { title: string; description?: string; onRetry?: () => void };

export const ErrorState = ({ title, description, onRetry }: ErrorStateProps) => (
  <Alert variant="destructive">
    <AlertCircle className="h-4 w-4" />
    <AlertTitle>{title}</AlertTitle>
    <AlertDescription className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <span>{description ?? "Something went wrong while loading. Your data is safe."}</span>
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry}>
          <RotateCw className="h-4 w-4" aria-hidden="true" />
          Try again
        </Button>
      )}
    </AlertDescription>
  </Alert>
);
```

Rules:
- Skeletons match the real layout's size and position, so nothing jumps when data arrives.
- Avoid flashing: if data usually arrives in under ~200ms, delay showing the skeleton (`useDeferredValue`, or a short delay) instead of flashing it for one frame.
- Never block the whole page with a full-screen spinner.
- Add `aria-busy="true"` on the region that is loading, and `aria-live="polite"` on areas whose content updates after load.
