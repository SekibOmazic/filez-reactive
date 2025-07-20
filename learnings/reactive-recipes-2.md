### 1. Why the Solution is Non-Blocking: The Reactive "Recipe"

The entire process is non-blocking because we never tell a thread to "wait." Instead, we build a **reactive pipeline**, which is like a detailed recipe of operations. The system only executes a step when the necessary ingredients (data) for that step are available. This is managed by Project Reactor's event loop.

Here's the journey of the request, highlighting the non-blocking nature:

1.  **The Request Arrives:** When the POST request hits your `FileController`, WebFlux accepts it on an I/O thread. It sees you want to stream a response and immediately prepares to do so.

2.  **The Controller Returns a `Flux`:** Your controller method calls `streamingZipService.createZipStream(...)` and immediately returns a `ResponseEntity` containing a `Flux<ByteBuffer>`. This is the crucial part: **it returns the *recipe*, not the result.** The controller's work is done in microseconds. The thread that handled the request is now free to serve other users.

3.  **The Pipeline is Assembled:** Inside `createZipStream`, we use `Flux.concat()` and `Flux.map()`. These operators don't execute anything yet. They simply build up the chain of events:
    *   "First, for `key1`, create an entry stream."
    *   "When that *entire stream* is finished, then for `key2`, create its entry stream."
    *   "When all file entry streams are finished, *then* subscribe to the `Mono` that creates the central directory."

4.  **The Real Work (Asynchronous I/O):** When WebFlux subscribes to this final `Flux`, the recipe begins to execute.
    *   The call to `Mono.fromFuture(s3AsyncClient.getObject(...))` is the key. The AWS `S3AsyncClient` takes the request, hands it off to its own dedicated, low-level I/O thread pool, and **immediately returns a `CompletableFuture`**.
    *   Our application doesn't block. It attaches a callback via `flatMapMany` that says, "When that future completes and gives you the data publisher, start creating a `Flux` from it."
    *   All the CPU-intensive work (like compressing a chunk with `Deflater`) happens in small bursts on worker threads as data becomes available.

Because no thread ever sits idle waiting for the network, a small number of threads can efficiently handle a massive number of concurrent requests.

### 2. How We Avoid Storing the ZIP in Memory: A River, Not a Lake

This is the second pillar of the solution: we treat the data as a **flowing stream (a river)**, not as a complete object to be held in memory (a lake). The memory footprint remains low and constant because we only ever hold a small "bucket" of water at a time.

Let's trace the data flow for a single file entry within the ZIP:

![Streaming ZIP Pipeline](https://storage.googleapis.com/gweb-cloud-ai-images/user-gen-images/J07v4pQpQW7M1f1A7T3K1673895028004.png)

1.  **Station 1: The Local Header:** We create and emit a tiny `ByteBuffer` (30-100 bytes) for the local file header. This is immediately sent to the client. Memory usage: negligible.

2.  **Station 2: The Data Stream (The Core Process):**
    *   The `s3FileStream` begins emitting small chunks of the source file from S3 (e.g., 64KB).
    *   Our `concatMap` operator receives **one 64KB chunk**.
    *   It passes this chunk to the `Deflater` to be compressed. The `Deflater` works on this small chunk and produces one or more even smaller compressed chunks.
    *   These tiny compressed chunks are emitted downstream and sent to the client.
    *   The 64KB buffer and the small compressed buffers are now eligible for garbage collection.
    *   This process repeats for the next 64KB chunk from S3.
        **The entire multi-gigabyte file is never in memory at once.** We only ever hold a few kilobytes of data that are actively being compressed.

3.  **Station 3: The Data Descriptor:** After the file stream from S3 is complete, we create and emit another tiny `ByteBuffer` (16 bytes) for the data descriptor. Memory usage: negligible.

This entire sequence (`Header` -> `Data Chunks` -> `Descriptor`) repeats for every file in the list.

### 3. The One Necessary Compromise: The Central Directory

There is one part that *must* be held in memory before it's sent: the metadata for the **Central Directory**.

*   **Why?** The Central Directory acts as the "table of contents" at the very end of the ZIP file. To build it, you need to know information about every file that has already been streamed (its compressed size, CRC checksum, and its starting position/offset in the stream).
*   **Why It's Okay:** We are not storing the file *content*. We are only storing the tiny `ZipEntryInfo` objects. Even for a ZIP with 10,000 files, the list of these metadata objects would only consume a few megabytes of memory, not the gigabytes of the files themselves. This is a perfectly acceptable trade-off for a low memory footprint.

Our code correctly handles this by collecting the `ZipEntryInfo` objects as a side-effect and only subscribing to the `createCentralDirectoryStream` Mono **after** the main data `Flux` has completed, ensuring the list is fully populated.