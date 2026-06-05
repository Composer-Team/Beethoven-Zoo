module shake256 (
    input wire clk,
    input wire rst_n,

    input wire start,

    // Absorb
    input wire [63:0] data_in,
    input wire data_in_valid,
    output wire data_in_ready,
    input wire data_in_last,

    // Squeeze
    input wire [12:0] out_len,
    output wire [63:0] data_out,
    output wire data_out_valid,
    input wire data_out_ready,
    output wire data_out_last,
    output wire done
);

    localparam [2:0] IDLE = 3'd0;
    localparam [2:0] ABSORB = 3'd1;
    localparam [2:0] ABSORB_PERM = 3'd2;
    localparam [2:0] PAD = 3'd3;
    localparam [2:0] PAD_PERM = 3'd4;
    localparam [2:0] SQUEEZE = 3'd5;
    localparam [2:0] SQUEEZE_PERM = 3'd6;
    localparam [2:0] DONE = 3'd7;
    localparam RATE_LANES = 5'd17;

    reg [2:0] fsm_state;
    reg [1599:0] keccak_state;
    reg [4:0] lane_counter;
    reg [12:0] output_len_reg;
    reg [12:0] output_count;

    reg perm_start;
    wire perm_ready;
    wire perm_done;
    wire [1599:0] perm_state_out;

    keccak_f1600 keccak (
        .clk (clk),
        .rst_n (rst_n),
        .start (perm_start),
        .state_in (keccak_state),
        .state_out (perm_state_out),
        .ready (perm_ready),
        .done (perm_done)
    );

    // Current lane
    wire [63:0] current_lane = keccak_state[lane_counter*64 +: 64];

    // Squeeze logic
    wire [12:0] bytes_remaining = output_len_reg - output_count;
    wire is_last_squeeze = (bytes_remaining <= 13'd8);

    // FSM
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            fsm_state <= IDLE;
            keccak_state <= 1600'd0;
            lane_counter <= 5'd0;
            output_len_reg <= 13'd0;
            output_count <= 13'd0;
            perm_start <= 1'b0;
        end
        else begin
            perm_start <= 1'b0; // Default

            case (fsm_state)
                IDLE: begin
                    if (start) begin
                        fsm_state <= ABSORB;
                        keccak_state <= 1600'd0;
                        lane_counter <= 5'd0;
                        output_len_reg <= out_len;
                        output_count <= 13'd0;
                    end
                end

                ABSORB: begin
                    if (data_in_valid) begin
                        keccak_state[lane_counter*64 +: 64] <= current_lane ^ data_in;

                        if (data_in_last) begin
                            fsm_state <= PAD;
                        end
                        else if (lane_counter == RATE_LANES - 1) begin
                            // Rate block full, trigger permutation
                            lane_counter <= 5'd0;
                            perm_start <= 1'b1;
                            fsm_state <= ABSORB_PERM;
                        end
                        else begin
                            lane_counter <= lane_counter + 1'd1;
                        end
                    end
                end

                ABSORB_PERM: begin
                    if (perm_done) begin
                        keccak_state <= perm_state_out;
                        lane_counter <= 5'd0;
                        fsm_state <= ABSORB;
                    end
                end

                PAD: begin
                    // Fixed 16-byte absorb: 0x1F at byte 0 of lane 2 (bit 128),
                    // 0x80 at byte 7 of lane 16 (bit 1080 = 16*64+56)
                    keccak_state[128 +: 8] <= keccak_state[128 +: 8] ^ 8'h1F;
                    keccak_state[(RATE_LANES-1)*64 + 56 +: 8] <= keccak_state[(RATE_LANES-1)*64 + 56 +: 8] ^ 8'h80;

                    perm_start <= 1'b1;
                    fsm_state <= PAD_PERM;
                end

                PAD_PERM: begin
                    if (perm_done) begin
                        keccak_state <= perm_state_out;
                        lane_counter <= 5'd0;
                        output_count <= 13'd0;

                        if (output_len_reg == 13'd0)
                            fsm_state <= DONE;
                        else
                            fsm_state <= SQUEEZE;
                    end
                end

                SQUEEZE: begin
                    if (data_out_ready) begin
                        if (bytes_remaining >= 13'd8)
                            output_count <= output_count + 13'd8;
                        else
                            output_count <= output_len_reg;

                        if (is_last_squeeze) begin
                            fsm_state <= DONE;
                        end
                        else if (lane_counter == RATE_LANES - 1) begin
                            lane_counter <= 5'd0;
                            perm_start <= 1'b1;
                            fsm_state <= SQUEEZE_PERM;
                        end
                        else begin
                            lane_counter <= lane_counter + 1'd1;
                        end
                    end
                end

                SQUEEZE_PERM: begin
                    if (perm_done) begin
                        keccak_state <= perm_state_out;
                        lane_counter <= 5'd0;
                        fsm_state <= SQUEEZE;
                    end
                end

                DONE: begin
                    if (start) begin
                        keccak_state <= 1600'b0;
                        lane_counter <= 5'd0;
                        output_len_reg <= out_len;
                        output_count <= 13'd0;
                        fsm_state <= ABSORB;
                    end
                end

                default: fsm_state <= IDLE;
            endcase
        end
    end

    assign data_in_ready = (fsm_state == ABSORB);
    assign data_out = current_lane;
    assign data_out_valid = (fsm_state == SQUEEZE);
    assign done = (fsm_state == DONE);
    assign data_out_last = (fsm_state == SQUEEZE) && is_last_squeeze;

endmodule