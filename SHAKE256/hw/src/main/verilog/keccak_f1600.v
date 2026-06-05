module keccak_f1600 (
    input  wire clk,
    input  wire rst_n,
    input  wire start,
    input  wire [1599:0] state_in,
    output wire [1599:0] state_out,
    output wire ready,
    output wire done
);

    // FSM states
    localparam IDLE = 1'b0;
    localparam RUNNING = 1'b1;

    reg fsm_state;
    reg [4:0] round_counter;
    reg [1599:0] state_reg;

    // Round wires
    wire [1599:0] round_out;

    // Single round
    keccak_round k_round (
        .state_in (state_reg),
        .round (round_counter),
        .state_out (round_out)
    );

    // FSM and datapath
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            fsm_state <= IDLE;
            round_counter <= 5'd0;
            state_reg <= 1600'd0;
        end
        else begin
            case (fsm_state)
                IDLE: begin
                    if (start) begin
                        fsm_state <= RUNNING;
                        round_counter <= 5'd0;
                        state_reg <= state_in;
                    end
                end

                RUNNING: begin
                    state_reg <= round_out;
                    round_counter <= round_counter + 1'd1;

                    if (round_counter == 5'd23) begin
                        fsm_state <= IDLE;
                    end
                end
            endcase
        end
    end

    // Done: registered so it fires after state_reg captures the final round
    reg done_r;
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n)
            done_r <= 1'b0;
        else
            done_r <= (fsm_state == RUNNING) && (round_counter == 5'd23);
    end

    // Outputs
    assign state_out = state_reg;
    assign ready = (fsm_state == IDLE);
    assign done = done_r;

endmodule