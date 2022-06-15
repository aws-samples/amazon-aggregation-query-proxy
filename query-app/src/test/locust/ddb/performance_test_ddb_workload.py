# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: MIT-0

from locust import User, task, between, Locust, TaskSet, web, HttpLocust
from random import randrange, choice, uniform
from datetime import timedelta, datetime
from locust.runners import MasterRunner, WorkerRunner
from locust import events
from botocore.exceptions import ClientError

import logging
import json
import boto3
import os
import sys
import botocore
import uuid
import time

"""
Init parameters
"""
table_name='"performance_test_table"'
number_accounts = 100
number_customers = 100
number_users = 1000
aws_region = "us-east-1"
status_codes = ["CREATED", "PAYED", "CANCELED", "PROCESSED", "SHIPPED", "DELIVERED", "RETURNED"]
d1 = datetime.strptime('1/1/2021 9:00 AM', '%m/%d/%Y %I:%M %p')
d2 = datetime.strptime('1/1/2022 9:00 PM', '%m/%d/%Y %I:%M %p')

def get_random_date(start, end):
    """
    The function returns a random datetime.
    """
    dt = end - start
    int_dt = (dt.days * 24 * 60 * 60) + dt.seconds
    random_second = randrange(int_dt)
    return start + timedelta(seconds=random_second)

def generate_map_values():
    """
    The function generates and maps values regarding to the schema
    """
    zipcode = randrange(11221, 99999)
    username = "user"+str(randrange(number_users))
    #PK: ACCOUNT#<ACCOUNT_NAME>#CUSTOMER#<CUSTOMER_NAME>, SK: STATUS#<STATUS>#ORDER#<ORDER_ID>
    account_name = "ACCOUNT"+str(randrange(number_accounts))
    customer_name = "CUSTOMER"+str(randrange(number_customers))
    status_code = choice(status_codes)
    order_id = str(uuid.uuid1())
    amount = uniform(0.001, 10000)
    pk = ["ACCOUNT", account_name, "CUSTOMER", customer_name]
    sk = ["STATUS", status_code, "ORDER", order_id]
    mapValues = {"pk": "#".join(pk),
              "sk": "#".join(sk),
              "zipcode": zipcode,
              "username": username,
              "datetime": str(get_random_date(d1, d2)),
              "status" : status_code,
              "amount": amount}
    return mapValues

def create_execute_statement_input(operation, mapValues, table_name):
    if (operation == "write"):
        return {
                "Statement": "INSERT INTO "+table_name+" value"+str(mapValues)
               }

    if (operation == "read"):
        account_name = "ACCOUNT"+str(randrange(number_accounts))
        customer_name = "CUSTOMER"+str(randrange(number_customers))
        status_code = choice(status_codes)
        pk = "#".join(["ACCOUNT", account_name, "CUSTOMER", customer_name])
        sk = "#".join(["STATUS", status_code])
        return {
            "Statement": "SELECT * FROM "+table_name+" WHERE pk=? AND CONTAINS('sk', ?)",
            "Parameters": [{"S":pk},{"S":sk}],
            "ConsistentRead": False
        }

def execute_statement(dynamodb_client, input):
    try:
        response = dynamodb_client.execute_statement(**input)
        # Handle response
    except ClientError as error:
        handle_error(error)

def handle_error(error):
    error_code = error.response['Error']['Code']
    error_message = error.response['Error']['Message']
    error_help_string = ERROR_HELP_STRINGS[error_code]

    print('[{error_code}] {help_string}. Error message: {error_message}'
          .format(error_code=error_code,
                  help_string=error_help_string,
                  error_message=error_message))

def create_dynamodb_client(region=aws_region):
    client_config = botocore.config.Config(
        max_pool_connections=10,
    )
    return boto3.client("dynamodb", config=client_config, region_name=region)

global dynamodb_client

class DDBClient:
    _locust_environment = None
    def __getattr__(self, name):
        def wrapper(*args, **kwargs):
            start_time = time.time()
            try:
                result = execute_statement(*args, **kwargs)
                events.request_success.fire(request_type="partiQL",
                                            name=operation_name,
                                            response_time=int((time.time() - start_time) * 1000),
                                            response_length=512)
            except ClientError as e:
                events.request_failure.fire(request_type="partiQL",
                                            name="Write Timeout",
                                            response_time=int((time.time() - start_time) * 1000),
                                            exception=e,
                                            response_length=1)
                print('error {}'.format(e))
        return wrapper

class DDBUser(User):
    abstract = True
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.client = DDBClient()
        self.client._locust_environment = self.environment

class ApiUser(DDBUser):
    global dynamodb_client
    dynamodb_client = create_dynamodb_client(region=aws_region)
    wait_time = between(0, 1)

    @task
    def write_to_ddb(self):
        global operation_name
        operation_name = "write_to_ddb"
        mapValues = generate_map_values()
        partiql_statement_input = create_execute_statement_input("write", mapValues, table_name)
        self.client.execute_statement(dynamodb_client, partiql_statement_input)

    #@task
    def read_from_ddb(self):
        global operation_name
        operation_name = "read_from_ddb"
        mapValues = generate_map_values()
        partiql_statement_input = create_execute_statement_input("read", mapValues, table_name)
        self.client.execute_statement(dynamodb_client, partiql_statement_input)