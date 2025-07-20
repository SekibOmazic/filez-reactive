### 1. Why the Solution is Non-Blocking: The Reactive Chain

The entire solution is non-blocking because it's built on a foundation of reactive programming from start to finish. Instead of one thread handling a request from beginning to end and getting "blocked" while waiting for network or disk I/O, the work is handled by an event loop.

Here's the step-by-step flow:

1.  **Request Arrival (WebFlux):** When a file upload request hits your `FileController`, Spring WebFlux (which uses a non-blocking server like Netty) accepts the connection. A worker thread picks it up but doesn't wait for the whole file. It just notes, "Data will be arriving on this connection."

2.  **The `Flux<ByteBuffer>`:** The controller receives a `Flux<ByteBuffer>`. This is not the file data itself; it's a **subscription**, a promise that chunks of data will arrive in the future. No memory has been allocated for the whole file.

3.  **Passing the Promise:** The `Flux` is passed from the controller to the `S3Service` and then to our `StreamingMultipartS3Uploader`. At this point, still very little work has been done. The chain of operations has been defined, but not yet executed.

4.  **Asynchronous SDK Calls:** The magic happens in the uploader. Every call to the `S3AsyncClient` (like `createMultipartUpload`, `uploadPart`, etc.) is non-blocking.
    *   When we call `Mono.fromFuture(s3AsyncClient.createMultipartUpload(...))`, the SDK immediately returns a `CompletableFuture` and sends the request to S3 on a separate I/O thread.
    *   Our main application thread is now **free**. It doesn't wait. It can go and handle other incoming requests.
    *   When S3 responds, the event loop is notified, and it schedules the *next* part of our reactive chain (the `flatMap` operator) to run on a worker thread.

Think of it like a non-blocking waiter at a restaurant. Instead of taking an order, going to the kitchen, and waiting for the food to be cooked (blocking), they give the order to the kitchen and immediately go serve other tables. They are notified when the food is ready (a callback). Our application threads are the non-blocking waiters, making them incredibly efficient.

### 2. Why It Avoids Storing the Whole File in Memory: The Streaming Approach

This is the key to handling large files. The solution treats the file as a flowing river, not a lake. We only ever hold a small bucket of water at a time.

1.  **The Source Stream:** As WebFlux receives data from the client's upload, it emits the data as small `ByteBuffer` chunks into the `Flux`.

2.  **`bufferTimeout(PART_SIZE_IN_BYTES, ...)`:** This reactive operator is the heart of the memory management. It subscribes to the incoming `Flux` of small chunks and does one simple thing: it collects them into a list.
    *   As soon as the total size of the chunks in its list reaches **5MB** (our `PART_SIZE_IN_BYTES`), it emits that list of chunks as a single item downstream.
    *   It then starts a new, empty list and begins collecting the next 5MB.

3.  **Processing in Chunks (`concatMap`):** The `concatMap` operator receives this 5MB list (`List<ByteBuffer>`). The `uploadPart` method then allocates a single `ByteBuffer` of *just that 5MB size*, copies the chunks into it, and sends it to S3. Once that upload is complete, the 5MB buffer is eligible for garbage collection.

The memory usage of your application remains **constant and low** regardless of the file size. Whether the file is 10MB, 10GB, or 100GB, the service only ever holds about 5-10MB of the file's content in memory at any given moment. This is what makes it so scalable and resilient to `OutOfMemoryError` exceptions.

### 3. Why It Works Universally (AWS, MinIO, S3Mock): The Power of the API Contract

This is the most important architectural benefit of our final solution.

The S3 API is a standardized **contract**. To be "S3-compatible," an object storage provider must correctly implement the rules of this API. The multipart upload process (`CreateMultipartUpload` -> `UploadPart` -> `CompleteMultipartUpload`) is a fundamental part of that contract for handling large objects.

*   **High-End Provider (AWS S3):** This is the "native speaker" of the API. Our code works perfectly because it's speaking the language AWS defined.

*   **Low-End / Mocked Provider (MinIO, S3Mock):** The entire purpose of these tools is to provide a simulation that **honors the S3 API contract**. They must correctly implement the multipart upload flow to be useful. Our code works because it speaks this common, universal language. It doesn't use any special shortcuts or proprietary features.

**Why the Previous `S3TransferManager` Approach Failed:**

The `S3TransferManager` backed by the CRT client is a high-level **optimizer**. It's not just a simple client; it contains complex internal logic and native code that makes assumptions based on the behavior of the real AWS S3 service. When we pointed it at a simple HTTP mock like S3Mock, its advanced logic failed because the mock didn't respond with the exact special behavior the optimizer expected. It was like trying to speak a complex, technical dialect to someone who only understands the basic language.

By manually implementing the multipart upload flow, we switched from the "specialized dialect" to the "base language" that every S3-compatible provider is guaranteed to understand. This makes our solution portable, robust, and universally compatible.