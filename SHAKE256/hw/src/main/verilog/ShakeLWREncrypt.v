// Streaming LWR-PRF controller using SHAKE256.
//
// For each i in 0..count-1:
//   a = hash_to_vector(nonce, i) -- 445 elements in Z_{4096}
//   inner = <a, sk> -- dot product with binary secret key
//   prf_out = prf_rounding(inner) -- LWR rounding to Z_16
//
// PRF outputs are packed per byte (4 bits each) and streamed out.

module ShakeLWREncrypt #(
    parameter N_LWR = 445,
    parameter N = 2048,
    parameter P = 16,
    parameter MAX_COUNT = 1024
) (
    input wire clk,
    input wire rst_n,
    input wire start,

    input wire [63:0] nonce,
    input wire nonce_valid,
    output wire nonce_ready,

    input wire [N_LWR-1:0] sk,
    input wire sk_valid,
    output wire sk_ready,

    input wire [31:0] count,

    output reg [7:0] out_data,
    output reg out_valid,
    input wire out_ready,

    output wire idle
);

    localparam ELEM_WIDTH = $clog2(2*N);
    localparam ACC_WIDTH = $clog2(N_LWR) + ELEM_WIDTH;
    localparam ADDR_WIDTH = $clog2(N_LWR);
    localparam PRF_BITS = $clog2(P);
    localparam PACKED = 8 / PRF_BITS;
    localparam PACK_BITS = $clog2(PACKED);
    localparam BYTE_CNT_W = $clog2(MAX_COUNT);

    // Secret key latch
    reg [N_LWR-1:0] sk_reg;
    reg sk_latched;
    assign sk_ready = !sk_latched;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            sk_reg <= {N_LWR{1'b0}};
            sk_latched <= 1'b0;
        end else begin
            if (start && sk_valid) begin
                sk_reg <= sk;
                sk_latched <= 1'b1;
            end else if (start) begin
                sk_latched <= 1'b0;
            end else if (sk_valid && !sk_latched) begin
                sk_reg <= sk;
                sk_latched <= 1'b1;
            end
        end
    end

    // Nonce latch
    reg [63:0] nonce_reg;
    reg nonce_latched;
    assign nonce_ready = !nonce_latched;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            nonce_reg <= 64'd0;
            nonce_latched <= 1'b0;
        end else begin
            if (start && nonce_valid) begin
                nonce_reg <= nonce;
                nonce_latched <= 1'b1;
            end else if (start) begin
                nonce_latched <= 1'b0;
            end else if (nonce_valid && !nonce_latched) begin
                nonce_reg <= nonce;
                nonce_latched <= 1'b1;
            end
        end
    end

    // Count latch
    reg [31:0] count_r;
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) count_r <= 32'd0;
        else if (start) count_r <= count;
    end

    // Sub-module control and data wires
    reg htv_start;
    reg dp_start;
    wire [ELEM_WIDTH-1:0] hash_out;
    wire [ADDR_WIDTH-1:0] hash_idx;
    wire hash_valid;
    wire hash_last;
    wire htv_done;
    wire [ACC_WIDTH-1:0] dot_prod;
    wire dot_done;
    wire [PRF_BITS-1:0] prf;

    // Vector loop counter
    reg [31:0] vec_idx;

    hash_to_vector #(
        .N_LWR(N_LWR),
        .N(N),
        .ELEM_WIDTH(ELEM_WIDTH)
    ) htv (
        .clk(clk),
        .rst_n(rst_n),
        .start(htv_start),
        .nonce(nonce_reg),
        .index({32'd0, vec_idx}),
        .hash_out(hash_out),
        .hash_idx(hash_idx),
        .hash_valid(hash_valid),
        .hash_last(hash_last),
        .done(htv_done)
    );

    dot_product #(
        .N_LWR(N_LWR),
        .ELEM_WIDTH(ELEM_WIDTH),
        .ACC_WIDTH(ACC_WIDTH)
    ) dp (
        .clk(clk),
        .rst_n(rst_n),
        .start(dp_start),
        .a_in(hash_out),
        .a_valid(hash_valid),
        .a_last(hash_last),
        .key_bit(sk_reg[hash_idx]),
        .dot_product(dot_prod),
        .done(dot_done)
    );

    prf_rounding #(
        .N(N),
        .P(P),
        .ACC_WIDTH(ACC_WIDTH)
    ) rnd (
        .inner_product(dot_prod),
        .prf_out(prf)
    );

    // FSM
    localparam ST_IDLE = 2'd0;
    localparam ST_HASH_START = 2'd1;
    localparam ST_HASH_WAIT = 2'd2;
    localparam ST_EMIT = 2'd3;

    reg [1:0] fsm_state;
    reg [7:0] out_data_r;
    reg [PACK_BITS-1:0] fill_cnt;
    reg [BYTE_CNT_W-1:0] byte_cnt;
    reg running;

    assign idle = !running;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            fsm_state <= ST_IDLE;
            htv_start <= 1'b0;
            dp_start <= 1'b0;
            vec_idx <= 32'd0;
            out_data_r <= 8'd0;
            out_data <= 8'd0;
            out_valid <= 1'b0;
            fill_cnt <= {PACK_BITS{1'b0}};
            byte_cnt <= {BYTE_CNT_W{1'b0}};
            running <= 1'b0;
        end else begin
            htv_start <= 1'b0; // default: single-cycle pulse
            dp_start <= 1'b0;

            case (fsm_state)
                ST_IDLE: begin
                    if (start) begin
                        vec_idx <= 32'd0;
                        out_data_r <= 8'd0;
                        fill_cnt <= {PACK_BITS{1'b0}};
                        byte_cnt <= {BYTE_CNT_W{1'b0}};
                        out_valid <= 1'b0;
                        running <= 1'b1;
                        fsm_state <= ST_HASH_START;
                    end
                end

                // Wait for nonce and sk to be latched, then pulse start for one cycle
                ST_HASH_START: begin
                    if (nonce_latched && sk_latched) begin
                        htv_start <= 1'b1;
                        dp_start <= 1'b1;
                        fsm_state <= ST_HASH_WAIT;
                    end
                end

                // Wait for dot_product to finish accumulating 445 elements.
                // hash_to_vector drives hash_valid/hash_out/hash_last directly
                // into dot_product via combinational wires -- no FSM action needed.
                ST_HASH_WAIT: begin
                    if (dot_done) begin
                        // Pack PRF output into current output byte
                        out_data_r <= out_data_r | ({{(8-PRF_BITS){1'b0}}, prf} << (fill_cnt * PRF_BITS));

                        if (fill_cnt == PACKED - 1) begin
                            // Byte complete: latch final value and signal consumer
                            out_data <= out_data_r | ({{(8-PRF_BITS){1'b0}}, prf} << (fill_cnt * PRF_BITS));
                            out_valid <= 1'b1;
                            out_data_r <= 8'd0;
                            fill_cnt <= {PACK_BITS{1'b0}};
                            fsm_state <= ST_EMIT;
                        end else begin
                            // More PRF outputs needed to complete this byte
                            fill_cnt <= fill_cnt + 1;
                            vec_idx <= vec_idx + 1;
                            fsm_state <= ST_HASH_START;
                        end
                    end
                end

                // Hold out_valid until consumer accepts, then advance loop
                ST_EMIT: begin
                    if (out_valid && out_ready) begin
                        out_valid <= 1'b0;
                        byte_cnt <= byte_cnt + 1;
                        if (byte_cnt + 1 == count_r >> PACK_BITS) begin
                            // All bytes emitted
                            running <= 1'b0;
                            fsm_state <= ST_IDLE;
                        end else begin
                            vec_idx <= vec_idx + 1;
                            fsm_state <= ST_HASH_START;
                        end
                    end
                end

                default: fsm_state <= ST_IDLE;
            endcase
        end
    end

endmodule