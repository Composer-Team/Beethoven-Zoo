module hash_to_vector #(
    parameter N_LWR = 445,
    parameter N = 2048,
    parameter ELEM_WIDTH = 12
) (
    input wire clk,
    input wire rst_n,
    input wire start,
    input wire [63:0] nonce,
    input wire [63:0] index,

    output reg [ELEM_WIDTH-1:0] hash_out,
    output reg [$clog2(N_LWR)-1:0] hash_idx,
    output reg hash_valid,
    output reg hash_last,
    output reg done
);

    // SHAKE256 rate: 1088 bits = 17 x 64-bit lanes = 136 bytes per squeeze block
    localparam RATE_BITS = 1088;
    localparam RATE_LANES = RATE_BITS / 64;
    // Elements extractable per block: floor(RATE_BITS / ELEM_WIDTH)
    // Top (RATE_BITS mod ELEM_WIDTH) = 8 bits of each block are discarded
    localparam ELEMS_PER_BLK = RATE_BITS / ELEM_WIDTH;
    // Blocks needed to cover N_LWR elements (ceiling division)
    localparam N_BLOCKS = (N_LWR + ELEMS_PER_BLK - 1) / ELEMS_PER_BLK;
    // Total bytes to squeeze from shake256
    localparam [12:0] OUT_BYTES = N_BLOCKS * (RATE_BITS / 8);

    // SHAKE256 absorb
    reg shake_start;
    reg [63:0] shake_data_in;
    reg shake_data_in_valid;
    reg shake_data_in_last;
    wire shake_data_in_ready;

    // SHAKE256 squeeze
    wire [63:0] shake_data_out;
    wire shake_data_out_valid;
    reg shake_data_out_ready;
    wire shake_data_out_last;
    wire shake_done;

    shake256 shake (
        .clk (clk),
        .rst_n (rst_n),
        .start (shake_start),
        .data_in (shake_data_in),
        .data_in_valid (shake_data_in_valid),
        .data_in_ready (shake_data_in_ready),
        .data_in_last (shake_data_in_last),
        .out_len (OUT_BYTES),
        .data_out (shake_data_out),
        .data_out_valid (shake_data_out_valid),
        .data_out_ready (shake_data_out_ready),
        .data_out_last (shake_data_out_last),
        .done (shake_done)
    );

    // Total element counter (0..N_LWR-1)
    reg [$clog2(N_LWR)-1:0] counter;

    // Block collection registers
    reg [RATE_BITS-1:0] block_buf;
    reg [$clog2(RATE_LANES)-1:0] lane_cnt;
    reg [$clog2(ELEMS_PER_BLK)-1:0] elem_in_block;

    // State encoding
    localparam IDLE = 3'd0;
    localparam ABSORB_NONCE = 3'd1;
    localparam ABSORB_INDEX = 3'd2;
    localparam SQ_COLLECT = 3'd3; // receive RATE_LANES lanes into block_buf
    localparam SQ_EXTRACT = 3'd4; // output ELEM_WIDTH-bit elements from block_buf
    localparam DONE_STATE = 3'd5;

    reg [2:0] state;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            state <= IDLE;
            counter <= 0;
            lane_cnt <= 0;
            elem_in_block <= 0;
            block_buf <= 0;
            hash_out <= 0;
            hash_idx <= 0;
            hash_valid <= 0;
            hash_last <= 0;
            done <= 0;
            shake_start <= 0;
            shake_data_in <= 0;
            shake_data_in_valid <= 0;
            shake_data_in_last <= 0;
            shake_data_out_ready <= 0;
        end else begin
            shake_start <= 0; // default: single-cycle pulse

            case (state)
                IDLE: begin
                    hash_valid <= 0;
                    hash_last <= 0;
                    done <= 0;
                    shake_data_in_valid <= 0;
                    shake_data_out_ready <= 0;

                    if (start) begin
                        shake_start <= 1;
                        counter <= 0;
                        lane_cnt <= 0;
                        state <= ABSORB_NONCE;
                    end
                end

                // Wait for SHAKE256 to enter absorb state, then drive nonce.
                ABSORB_NONCE: begin
                    if (shake_data_in_ready) begin
                        shake_data_in <= nonce;
                        shake_data_in_valid <= 1;
                        shake_data_in_last <= 0;
                        state <= ABSORB_INDEX;
                    end
                end

                // SHAKE256 consumes the nonce.
                // Load index so SHAKE256 sees it next cycle.
                // Index is the final absorb word.
                ABSORB_INDEX: begin
                    shake_data_in <= index;
                    shake_data_in_valid <= 1;
                    shake_data_in_last <= 1;
                    state <= SQ_COLLECT;
                end

                // Receive RATE_LANES x 64-bit lanes from shake256 into block_buf.
                // Lane i stored at block_buf[i*64 +: 64].
                // Top (RATE_BITS mod ELEM_WIDTH) = 8 bits are discarded in SQ_EXTRACT.
                SQ_COLLECT: begin
                    shake_data_in_valid <= 0;
                    shake_data_in_last <= 0;
                    shake_data_out_ready <= 1;
                    hash_valid <= 0;

                    if (shake_data_out_valid && shake_data_out_ready) begin
                        block_buf[lane_cnt * 64 +: 64] <= shake_data_out;
                        if (lane_cnt == RATE_LANES - 1) begin
                            lane_cnt <= 0;
                            elem_in_block <= 0;
                            state <= SQ_EXTRACT;
                        end else begin
                            lane_cnt <= lane_cnt + 1;
                        end
                    end
                end

                // Output ELEM_WIDTH-bit elements from block_buf, one per cycle.
                // Element j = block_buf[j*ELEM_WIDTH +: ELEM_WIDTH], j = 0..ELEMS_PER_BLK-1.
                // Last block stops early at N_LWR-1 total elements.
                SQ_EXTRACT: begin
                    shake_data_out_ready <= 0;

                    hash_out <= block_buf[elem_in_block * ELEM_WIDTH +: ELEM_WIDTH];
                    hash_idx <= counter;
                    hash_valid <= 1;
                    hash_last <= (counter == N_LWR - 1);

                    if (counter == N_LWR - 1) begin
                        state <= DONE_STATE;
                    end else begin
                        counter <= counter + 1;
                        if (elem_in_block == ELEMS_PER_BLK - 1) begin
                            elem_in_block <= 0;
                            lane_cnt <= 0;
                            state <= SQ_COLLECT;
                        end else begin
                            elem_in_block <= elem_in_block + 1;
                        end
                    end
                end

                DONE_STATE: begin
                    hash_valid <= 0;
                    hash_last <= 0;
                    shake_data_out_ready <= 0;
                    done <= 1;

                    if (start) begin
                        shake_start <= 1;
                        counter <= 0;
                        lane_cnt <= 0;
                        done <= 0;
                        state <= ABSORB_NONCE;
                    end
                end

                default: state <= IDLE;
            endcase
        end
    end

endmodule
