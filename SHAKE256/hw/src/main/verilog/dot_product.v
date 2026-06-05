module dot_product #(
    parameter N_LWR = 445,
    parameter ELEM_WIDTH = 12,
    parameter ACC_WIDTH = 32
) (
    input wire clk,
    input wire rst_n,
    input wire start,

    input wire [ELEM_WIDTH-1:0] a_in,
    input wire a_valid,
    input wire a_last,

    input wire key_bit,
    
    output reg [ACC_WIDTH-1:0] dot_product,
    output reg done
);

    reg [ACC_WIDTH-1:0] accumulator;
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            accumulator <= {ACC_WIDTH{1'b0}};
        end
        else if (start) begin
            accumulator <= 0;
        end
        else if (key_bit && a_valid) begin // if the key bit is 1 then add a
            accumulator <= accumulator + a_in;
        end
    end

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            dot_product <= {ACC_WIDTH{1'b0}};
            done <= 0;
        end
        else begin
            done <= 0;
            if (a_valid && a_last) begin
                if (key_bit) begin
                    dot_product <= accumulator + a_in;
                end
                else begin
                    dot_product <= accumulator;
                end
                done <= 1'b1;
            end
        end
    end

endmodule